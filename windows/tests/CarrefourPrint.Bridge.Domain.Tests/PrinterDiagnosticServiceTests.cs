using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>
/// Prova, com fakes, o contrato do modo de diagnóstico: nunca existe um resultado "impresso",
/// só Sent/Failed; os itens exigidos (bytes, erro técnico, resultado) são todos preenchidos
/// corretamente conforme o transporte simulado.
/// </summary>
public class PrinterDiagnosticServiceTests
{
    [Fact]
    public async Task Successful_send_reports_sent_with_byte_count_and_no_technical_error()
    {
        var service = new PrinterDiagnosticService(
            new FakeConnectionTester(reachable: true, message: "Impressora acessível", elapsedMillis: 5),
            FakePrinterTransport.AlwaysSucceeds());

        var result = await service.RunAsync("192.168.0.50", 9100, PrinterModel.TscMb241, "^XA^FDTeste^FS^XZ");

        Assert.Equal(DiagnosticSendOutcome.Sent, result.SendOutcome);
        Assert.Equal("^XA^FDTeste^FS^XZ".Length, result.BytesSent);
        Assert.Null(result.TechnicalError);
        Assert.Null(result.WireErrorCode);
        Assert.True(result.ConnectivityReachable);
    }

    [Fact]
    public async Task Failed_send_reports_failed_with_zero_bytes_and_technical_error()
    {
        var service = new PrinterDiagnosticService(
            new FakeConnectionTester(reachable: false, message: "Conexão recusada", elapsedMillis: 3),
            FakePrinterTransport.AlwaysFails(TransportFailureReason.ConnectionRefused));

        var result = await service.RunAsync("192.168.0.50", 9100, PrinterModel.TscMl241, "^XA^XZ");

        Assert.Equal(DiagnosticSendOutcome.Failed, result.SendOutcome);
        Assert.Equal(0, result.BytesSent);
        Assert.NotNull(result.TechnicalError);
        Assert.Equal("TRANSPORT_CONNECTION_REFUSED", result.WireErrorCode);
        Assert.False(result.ConnectivityReachable);
    }

    [Fact]
    public async Task Send_is_still_attempted_even_when_connectivity_probe_fails()
    {
        // Regra deliberada: conectividade e envio são medidos de forma independente — um pode
        // falhar sem impedir a tentativa do outro, para dar mais sinal de diagnóstico.
        var service = new PrinterDiagnosticService(
            new FakeConnectionTester(reachable: false, message: "Tempo de conexão esgotado", elapsedMillis: 1000),
            FakePrinterTransport.AlwaysSucceeds());

        var result = await service.RunAsync("192.168.0.50", 9100, PrinterModel.TscMb241, "^XA^XZ");

        Assert.False(result.ConnectivityReachable);
        Assert.Equal(DiagnosticSendOutcome.Sent, result.SendOutcome);
    }

    [Fact]
    public void Diagnostic_send_outcome_never_represents_a_confirmed_print()
    {
        // Verificação estrutural, não apenas de comportamento: o enum inteiro só tem dois valores
        // possíveis, nenhum deles "impresso" — a impossibilidade de representar esse estado é a
        // garantia, não uma checagem de runtime que poderia ser esquecida em algum caminho de código.
        var values = Enum.GetNames<DiagnosticSendOutcome>();
        Assert.Equal(["Sent", "Failed"], values);
    }

    private sealed class FakeConnectionTester(bool reachable, string message, long elapsedMillis) : IPrinterConnectionTester
    {
        public Task<ConnectionTestResult> TestAsync(Printer printer, CancellationToken ct = default) =>
            Task.FromResult(new ConnectionTestResult(reachable, message, elapsedMillis));
    }
}
