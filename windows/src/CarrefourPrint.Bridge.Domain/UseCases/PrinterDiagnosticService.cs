using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Resultado de envio no modo de diagnóstico. Deliberadamente NÃO existe um valor "Printed" neste
/// enum — em nenhum lugar do código isso é representável, porque nenhuma das duas plataformas tem
/// canal de confirmação física da impressora. O único resultado positivo possível é `Sent`
/// ("bytes entregues ao transporte"), nunca "impresso". Ver CARREFOUR-PRINT-CROSS-PLATFORM.md §7.
/// </summary>
public enum DiagnosticSendOutcome
{
    Sent,
    Failed,
}

/// <summary>
/// Registro completo de uma execução do modo de diagnóstico — cobre os 9 itens exigidos:
/// IP/porta informados, teste de conectividade, envio de ZPL de teste, horário, bytes, resultado,
/// erro técnico. A exportação do relatório (item 9) é responsabilidade da camada de
/// infraestrutura (PrinterDiagnosticReportExporter), que consome este registro.
/// </summary>
public sealed record PrinterDiagnosticResult
{
    public required DateTimeOffset StartedAt { get; init; }
    public required string IpAddress { get; init; }
    public required int Port { get; init; }
    public required PrinterModel Model { get; init; }
    public required string TestPayload { get; init; }

    public required bool ConnectivityReachable { get; init; }
    public required string ConnectivityMessage { get; init; }
    public required long ConnectivityElapsedMillis { get; init; }

    public required DiagnosticSendOutcome SendOutcome { get; init; }
    public required int BytesSent { get; init; }
    public required long SendElapsedMillis { get; init; }
    public string? TechnicalError { get; init; }
    public string? WireErrorCode { get; init; }
}

/// <summary>
/// Orquestra o modo de diagnóstico (Prompt do usuário — testar o Windows Bridge contra uma
/// impressora TSC ML241/MB241 real): roda o teste de conectividade (probe, sem payload) e o envio
/// de ZPL de teste (via o mesmo IPrinterTransport usado em produção — o diagnóstico testa o
/// caminho real, não um simulado), e consolida tudo em um único registro auditável.
///
/// Ambos os testes rodam sempre, mesmo se a conectividade falhar — um probe de socket vazio e um
/// envio de payload real podem falhar por razões diferentes (ex.: firewall bloqueando só uma das
/// portas), então reportar os dois dá mais sinal de diagnóstico do que abortar cedo.
/// </summary>
public sealed class PrinterDiagnosticService(
    IPrinterConnectionTester connectionTester,
    IPrinterTransport transport)
{
    public async Task<PrinterDiagnosticResult> RunAsync(
        string ipAddress,
        int port,
        PrinterModel model,
        string testPayload,
        CancellationToken ct = default)
    {
        var startedAt = DateTimeOffset.UtcNow;
        var printer = new Printer
        {
            Id = "diagnostic-probe",
            Name = "Sonda de diagnóstico",
            Model = model,
            IpAddress = ipAddress,
            Port = port,
            Protocol = PrinterProtocol.Tcp,
            Language = PrinterLanguage.Zpl,
            IsDefault = false,
            Enabled = true,
            CreatedAt = 0,
            UpdatedAt = 0,
        };

        var connectivity = await connectionTester.TestAsync(printer, ct);
        var payloadBytes = Encoding.UTF8.GetBytes(testPayload);
        var sendResult = await transport.SendAsync(printer, payloadBytes, ct);

        return sendResult switch
        {
            TransportResult.Success success => new PrinterDiagnosticResult
            {
                StartedAt = startedAt,
                IpAddress = ipAddress,
                Port = port,
                Model = model,
                TestPayload = testPayload,
                ConnectivityReachable = connectivity.Reachable,
                ConnectivityMessage = connectivity.Message,
                ConnectivityElapsedMillis = connectivity.ElapsedMillis,
                SendOutcome = DiagnosticSendOutcome.Sent,
                BytesSent = success.BytesSent,
                SendElapsedMillis = success.DurationMillis,
                TechnicalError = null,
                WireErrorCode = null,
            },
            TransportResult.Failure failure => new PrinterDiagnosticResult
            {
                StartedAt = startedAt,
                IpAddress = ipAddress,
                Port = port,
                Model = model,
                TestPayload = testPayload,
                ConnectivityReachable = connectivity.Reachable,
                ConnectivityMessage = connectivity.Message,
                ConnectivityElapsedMillis = connectivity.ElapsedMillis,
                SendOutcome = DiagnosticSendOutcome.Failed,
                BytesSent = 0,
                SendElapsedMillis = 0,
                TechnicalError = failure.TechnicalMessage,
                WireErrorCode = failure.Reason.WireErrorCode(),
            },
            _ => throw new InvalidOperationException($"TransportResult inesperado: {sendResult.GetType()}"),
        };
    }
}
