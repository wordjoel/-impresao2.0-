using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Validation;
using CarrefourPrint.Bridge.Infrastructure.Logging;
using Microsoft.Extensions.Logging;

namespace CarrefourPrint.Bridge.Infrastructure.Transport;

/// <summary>
/// Porta 1:1 de br.com.atos.printbridge.printing.TcpPrinterTransport (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §12.
/// Mesma ordem de checagem (endereço/porta sintáticos → rede disponível → conectar com timeout →
/// escrever → flush → fechar sempre), mesmo mapeamento de exceção para TransportFailureReason.
/// </summary>
public sealed class TcpPrinterTransport(
    IConfigurationRepository configurationRepository,
    INetworkStateProvider networkStateProvider,
    IEventLogger eventLogger) : IPrinterTransport
{
    public async Task<TransportResult> SendAsync(Printer printer, byte[] payload, CancellationToken ct = default)
    {
        if (!NetworkAddressValidator.IsValidAddress(printer.IpAddress) || !NetworkAddressValidator.IsValidPort(printer.Port))
        {
            return new TransportResult.Failure(TransportFailureReason.InvalidAddress, $"IP ou porta inválidos: {printer.IpAddress}:{printer.Port}");
        }

        if (!networkStateProvider.IsNetworkAvailable())
        {
            return new TransportResult.Failure(TransportFailureReason.NoNetwork, "Sem rede no dispositivo Windows");
        }

        var settings = await configurationRepository.CurrentAsync(ct);
        var stopwatch = Stopwatch.StartNew();
        using var client = new TcpClient();
        try
        {
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            connectCts.CancelAfter(settings.ConnectTimeoutMillis);
            try
            {
                // Conectar por IPAddress (não por string) quando o endereço já é um IP literal
                // evita a resolução dual-stack (Happy Eyeballs) que o overload de string aciona —
                // nesta máquina, medido em ~2s de atraso extra antes de um ECONNREFUSED chegar,
                // o que faria o timeout de conexão (tipicamente 5s, configurável) disparar antes
                // do refuse real em redes/hosts mais lentos. Impressoras são quase sempre
                // endereçadas por IP literal, então isso é o caminho comum, não uma otimização de
                // caso raro. Hostname (raro, mas válido conforme NetworkAddressValidator) ainda
                // passa pela resolução normal.
                if (IPAddress.TryParse(printer.IpAddress, out var literalIp))
                {
                    await client.ConnectAsync(literalIp, printer.Port, connectCts.Token);
                }
                else
                {
                    await client.ConnectAsync(printer.IpAddress, printer.Port, connectCts.Token);
                }
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new SocketException((int)SocketError.TimedOut);
            }

            using var stream = client.GetStream();
            stream.WriteTimeout = settings.SocketTimeoutMillis;
            using var writeCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            writeCts.CancelAfter(settings.SocketTimeoutMillis);
            try
            {
                await stream.WriteAsync(payload, writeCts.Token);
                await stream.FlushAsync(writeCts.Token);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new SocketException((int)SocketError.TimedOut);
            }

            stopwatch.Stop();
            eventLogger.Event(LogLevel.Information, "tcp_send_success", printerId: printer.Id,
                extra: new { bytes = payload.Length, elapsedMs = stopwatch.ElapsedMilliseconds });
            return new TransportResult.Success(payload.Length, stopwatch.ElapsedMilliseconds);
        }
        catch (SocketException e) when (e.SocketErrorCode == SocketError.TimedOut)
        {
            return Failure(printer.Id, TransportFailureReason.Timeout, e);
        }
        catch (SocketException e) when (e.SocketErrorCode == SocketError.ConnectionRefused)
        {
            return Failure(printer.Id, TransportFailureReason.ConnectionRefused, e);
        }
        catch (SocketException e)
        {
            return Failure(printer.Id, TransportFailureReason.IoError, e);
        }
        catch (Exception e)
        {
            return Failure(printer.Id, TransportFailureReason.Unknown, e);
        }
        finally
        {
            try
            {
                client.Close();
            }
            catch (Exception)
            {
                // Fechamento best-effort; já capturamos e reportamos a falha original acima.
            }
        }
    }

    private TransportResult.Failure Failure(string printerId, TransportFailureReason reason, Exception e)
    {
        eventLogger.Event(LogLevel.Error, "tcp_send_failure", printerId: printerId,
            extra: new { reason = reason.WireErrorCode(), exception = e.Message });
        return new TransportResult.Failure(reason, e.Message);
    }
}
