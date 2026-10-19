using System.Net;
using System.Net.Sockets;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Infrastructure.Transport;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

file sealed class StaticConfigRepo(AppConfiguration config) : IConfigurationRepository
{
    public Task<AppConfiguration> CurrentAsync(CancellationToken ct = default) => Task.FromResult(config);
    public Task UpdateAsync(Func<AppConfiguration, AppConfiguration> transform, CancellationToken ct = default) => Task.CompletedTask;
}

file sealed class StaticNetworkState(bool available) : INetworkStateProvider
{
    public bool IsNetworkAvailable() => available;
}

/// <summary>
/// Testa o probe de conectividade (item 3 do modo de diagnóstico) contra sockets reais — cobre
/// uma lacuna que nem o Android tinha (PrinterConnectionTesterImpl nunca teve teste dedicado,
/// ver CARREFOUR-PRINT-ANDROID-COMPONENT-AUDIT.md); aqui a peça é nova, então já nasce testada.
/// Confirma também a regra mais importante do probe: NUNCA escreve payload, só conecta e fecha.
/// </summary>
public class TcpPrinterConnectionTesterTests
{
    private static Printer PrinterFor(int port, string ip = "127.0.0.1") => new()
    {
        Id = "diagnostic-probe",
        Name = "Sonda",
        Model = PrinterModel.TscMb241,
        IpAddress = ip,
        Port = port,
        Protocol = PrinterProtocol.Tcp,
        Language = PrinterLanguage.Zpl,
        IsDefault = false,
        Enabled = true,
        CreatedAt = 0,
        UpdatedAt = 0,
    };

    [Fact]
    public async Task Reachable_printer_is_reported_as_reachable_without_receiving_any_payload()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;

        var bytesReceivedTask = Task.Run(async () =>
        {
            using var client = await listener.AcceptTcpClientAsync();
            // Dá um tempo curto para garantir que, se o tester escrevesse algo, chegaria aqui.
            await Task.Delay(200);
            return client.Available;
        });

        var tester = new TcpPrinterConnectionTester(
            new StaticConfigRepo(new AppConfiguration { ConnectTimeoutMillis = 2000 }),
            new StaticNetworkState(true));

        var result = await tester.TestAsync(PrinterFor(port));
        var bytesReceivedByServer = await bytesReceivedTask;
        listener.Stop();

        Assert.True(result.Reachable);
        Assert.Equal("Impressora acessível", result.Message);
        Assert.Equal(0, bytesReceivedByServer); // nenhum payload foi enviado pelo probe — só connect/close
    }

    [Fact]
    public async Task Unreachable_port_is_reported_as_not_reachable()
    {
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();

        var tester = new TcpPrinterConnectionTester(
            new StaticConfigRepo(new AppConfiguration { ConnectTimeoutMillis = 5000 }),
            new StaticNetworkState(true));

        var result = await tester.TestAsync(PrinterFor(port));

        Assert.False(result.Reachable);
    }

    [Fact]
    public async Task No_network_short_circuits_without_attempting_to_connect()
    {
        var tester = new TcpPrinterConnectionTester(
            new StaticConfigRepo(new AppConfiguration()),
            new StaticNetworkState(false));

        var result = await tester.TestAsync(PrinterFor(9100));

        Assert.False(result.Reachable);
        Assert.Contains("rede", result.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Invalid_address_is_rejected_before_touching_the_network()
    {
        var tester = new TcpPrinterConnectionTester(
            new StaticConfigRepo(new AppConfiguration()),
            new StaticNetworkState(true));

        var result = await tester.TestAsync(PrinterFor(9100, ip: "999.999.1.1"));

        Assert.False(result.Reachable);
        Assert.Equal("Endereço inválido", result.Message);
    }
}
