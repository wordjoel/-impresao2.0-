using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Validation;

namespace CarrefourPrint.Bridge.Infrastructure.Transport;

/// <summary>
/// Porta 1:1 de PrinterConnectionTesterImpl (Android) — só abre e fecha o socket (probe de
/// alcançabilidade), nunca escreve payload de impressão real, conforme exigido: não enviar
/// etiqueta durante o simples teste de porta.
/// </summary>
public sealed class TcpPrinterConnectionTester(
    IConfigurationRepository configurationRepository,
    INetworkStateProvider networkStateProvider) : IPrinterConnectionTester
{
    public async Task<ConnectionTestResult> TestAsync(Printer printer, CancellationToken ct = default)
    {
        var stopwatch = Stopwatch.StartNew();

        if (!NetworkAddressValidator.IsValidAddress(printer.IpAddress) || !NetworkAddressValidator.IsValidPort(printer.Port))
        {
            return new ConnectionTestResult(false, "Endereço inválido", stopwatch.ElapsedMilliseconds);
        }
        if (!networkStateProvider.IsNetworkAvailable())
        {
            return new ConnectionTestResult(false, "Dispositivo Windows sem conexão com a rede", stopwatch.ElapsedMilliseconds);
        }

        var settings = await configurationRepository.CurrentAsync(ct);
        using var client = new TcpClient();
        try
        {
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            connectCts.CancelAfter(settings.ConnectTimeoutMillis);
            try
            {
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

            return new ConnectionTestResult(true, "Impressora acessível", stopwatch.ElapsedMilliseconds);
        }
        catch (SocketException e) when (e.SocketErrorCode == SocketError.TimedOut)
        {
            return new ConnectionTestResult(false, "Tempo de conexão esgotado", stopwatch.ElapsedMilliseconds);
        }
        catch (SocketException e) when (e.SocketErrorCode == SocketError.ConnectionRefused)
        {
            return new ConnectionTestResult(false, "Conexão recusada", stopwatch.ElapsedMilliseconds);
        }
        catch (Exception e)
        {
            return new ConnectionTestResult(false, e.Message, stopwatch.ElapsedMilliseconds);
        }
        finally
        {
            try
            {
                client.Close();
            }
            catch (Exception)
            {
                // Fechamento best-effort.
            }
        }
    }
}
