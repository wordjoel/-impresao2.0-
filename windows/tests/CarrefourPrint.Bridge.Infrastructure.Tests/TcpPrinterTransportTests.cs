using System.Net;
using System.Net.Sockets;
using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Infrastructure.Logging;
using CarrefourPrint.Bridge.Infrastructure.Transport;
using Microsoft.Extensions.Logging.Abstractions;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

file sealed class StaticConfigurationRepository(AppConfiguration config) : IConfigurationRepository
{
    public Task<AppConfiguration> CurrentAsync(CancellationToken ct = default) => Task.FromResult(config);
    public Task UpdateAsync(Func<AppConfiguration, AppConfiguration> transform, CancellationToken ct = default) => Task.CompletedTask;
}

file sealed class StaticNetworkStateProvider(bool available) : INetworkStateProvider
{
    public bool IsNetworkAvailable() => available;
}

/// <summary>
/// Porta 1:1 de TcpPrinterTransportTest.kt (Android) — mesmo servidor TCP fake real (não mock),
/// mesmos 4 casos: entrega byte-a-byte exata, connection refused, sem rede, endereço inválido.
/// São EXATAMENTE os vetores de teste publicados em CARREFOUR-PRINT-CROSS-PLATFORM.md §15.1 —
/// a mesma entrada ("^XA^FDATOS PRINT BRIDGE^FS^XZ" etc.) deve produzir a mesma saída observável
/// (bytes idênticos no fio) tanto no Android quanto aqui.
/// </summary>
public class TcpPrinterTransportTests
{
    private static IEventLogger NullEventLogger() => new EventLogger(NullLogger<EventLogger>.Instance, new RecentEventsBuffer());

    private static Printer PrinterFor(int port) => new()
    {
        Id = "printer-1",
        Name = "Expedição 01",
        Model = PrinterModel.TscMb241,
        IpAddress = "127.0.0.1",
        Port = port,
        Protocol = PrinterProtocol.Tcp,
        Language = PrinterLanguage.Zpl,
        IsDefault = true,
        Enabled = true,
        CreatedAt = 0,
        UpdatedAt = 0,
    };

    [Fact]
    public async Task Sends_exact_zpl_bytes_to_the_fake_server()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var receiveTask = Task.Run(async () =>
        {
            using var client = await listener.AcceptTcpClientAsync();
            using var stream = client.GetStream();
            using var ms = new MemoryStream();
            await stream.CopyToAsync(ms);
            return ms.ToArray();
        });

        var transport = new TcpPrinterTransport(
            new StaticConfigurationRepository(new AppConfiguration { ConnectTimeoutMillis = 2000, SocketTimeoutMillis = 2000 }),
            new StaticNetworkStateProvider(true),
            NullEventLogger());

        var payload = Encoding.UTF8.GetBytes("^XA^FDATOS PRINT BRIDGE^FS^XZ");
        var result = await transport.SendAsync(PrinterFor(port), payload);

        var received = await receiveTask;
        listener.Stop();

        Assert.IsType<TransportResult.Success>(result);
        Assert.Equal(payload, received);
    }

    [Fact]
    public async Task Connection_refused_returns_connection_refused_failure()
    {
        // Porta sem listener nenhum (abrimos e fechamos imediatamente antes de conectar, igual ao teste Android).
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();

        var transport = new TcpPrinterTransport(
            new StaticConfigurationRepository(new AppConfiguration { ConnectTimeoutMillis = 5000, SocketTimeoutMillis = 5000 }),
            new StaticNetworkStateProvider(true),
            NullEventLogger());

        var result = await transport.SendAsync(PrinterFor(port), "^XA^XZ"u8.ToArray());

        var failure = Assert.IsType<TransportResult.Failure>(result);
        Assert.Equal(TransportFailureReason.ConnectionRefused, failure.Reason);
    }

    [Fact]
    public async Task No_network_short_circuits_without_attempting_to_connect()
    {
        var transport = new TcpPrinterTransport(
            new StaticConfigurationRepository(new AppConfiguration()),
            new StaticNetworkStateProvider(false),
            NullEventLogger());

        var result = await transport.SendAsync(PrinterFor(9100), "^XA^XZ"u8.ToArray());

        var failure = Assert.IsType<TransportResult.Failure>(result);
        Assert.Equal(TransportFailureReason.NoNetwork, failure.Reason);
    }

    [Fact]
    public async Task Invalid_address_is_rejected_before_touching_the_network()
    {
        var transport = new TcpPrinterTransport(
            new StaticConfigurationRepository(new AppConfiguration()),
            new StaticNetworkStateProvider(true),
            NullEventLogger());

        var result = await transport.SendAsync(PrinterFor(9100) with { IpAddress = "999.999.1.1" }, "^XA^XZ"u8.ToArray());

        var failure = Assert.IsType<TransportResult.Failure>(result);
        Assert.Equal(TransportFailureReason.InvalidAddress, failure.Reason);
    }
}
