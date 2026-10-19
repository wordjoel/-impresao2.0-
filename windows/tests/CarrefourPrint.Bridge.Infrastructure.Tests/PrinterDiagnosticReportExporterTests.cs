using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.License;
using CarrefourPrint.Bridge.Infrastructure.Logging;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Prova, lendo o arquivo de fato gerado (não a lógica em memória), que o relatório de
/// diagnóstico nunca afirma impressão física e sempre carrega a explicação técnica obrigatória —
/// em qualquer resultado, sucesso ou falha.
/// </summary>
public class PrinterDiagnosticReportExporterTests : IDisposable
{
    private readonly string _reportsDir = Path.Combine(Path.GetTempPath(), $"carrefour_diag_reports_{Guid.NewGuid():N}");
    private readonly WindowsCngDeviceIdentityProvider _identityProvider;
    private readonly string _keyName = $"CarrefourPrintBridgeTest_{Guid.NewGuid():N}";

    public PrinterDiagnosticReportExporterTests()
    {
        _identityProvider = new WindowsCngDeviceIdentityProvider(_keyName);
    }

    private static PrinterDiagnosticResult SentResult() => new()
    {
        StartedAt = DateTimeOffset.UtcNow,
        IpAddress = "192.168.0.50",
        Port = 9100,
        Model = PrinterModel.TscMb241,
        TestPayload = "^XA^FDTeste^FS^XZ",
        ConnectivityReachable = true,
        ConnectivityMessage = "Impressora acessível",
        ConnectivityElapsedMillis = 5,
        SendOutcome = DiagnosticSendOutcome.Sent,
        BytesSent = 42,
        SendElapsedMillis = 12,
        TechnicalError = null,
        WireErrorCode = null,
    };

    private static PrinterDiagnosticResult FailedResult() => new()
    {
        StartedAt = DateTimeOffset.UtcNow,
        IpAddress = "192.168.0.51",
        Port = 9100,
        Model = PrinterModel.TscMl241,
        TestPayload = "^XA^XZ",
        ConnectivityReachable = false,
        ConnectivityMessage = "Conexão recusada",
        ConnectivityElapsedMillis = 3,
        SendOutcome = DiagnosticSendOutcome.Failed,
        BytesSent = 0,
        SendElapsedMillis = 0,
        TechnicalError = "Nenhuma conexão pôde ser feita porque a máquina de destino as recusou ativamente",
        WireErrorCode = "TRANSPORT_CONNECTION_REFUSED",
    };

    [Fact]
    public void Successful_report_shows_sent_never_printed_and_carries_the_mandatory_disclaimer()
    {
        var exporter = new PrinterDiagnosticReportExporter(_identityProvider, _reportsDir);
        var path = exporter.Export(SentResult());
        var content = File.ReadAllText(path);

        Assert.Contains("RESULTADO: SENT", content);
        Assert.DoesNotContain("PRINTED", content, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("A impressão física NÃO foi confirmada", content);
        Assert.Contains("NÃO constitui homologação", content);
        Assert.Contains("TSC ML241/MB241", content);
    }

    [Fact]
    public void Failed_report_also_carries_the_mandatory_disclaimer_and_the_technical_error()
    {
        var exporter = new PrinterDiagnosticReportExporter(_identityProvider, _reportsDir);
        var path = exporter.Export(FailedResult());
        var content = File.ReadAllText(path);

        Assert.Contains("RESULTADO: FAILED", content);
        Assert.Contains("TRANSPORT_CONNECTION_REFUSED", content);
        Assert.Contains("A impressão física NÃO foi confirmada", content);
        Assert.Contains("NÃO constitui homologação", content);
    }

    [Fact]
    public void Report_records_all_nine_required_items()
    {
        var exporter = new PrinterDiagnosticReportExporter(_identityProvider, _reportsDir);
        var path = exporter.Export(SentResult());
        var content = File.ReadAllText(path);

        Assert.Contains("IP: 192.168.0.50", content);              // 1
        Assert.Contains("Porta: 9100", content);                    // 2
        Assert.Contains("Teste de conectividade", content);         // 3
        Assert.Contains("Envio de ZPL de teste", content);          // 4
        Assert.Contains("Horário registrado (UTC)", content);       // 5
        Assert.Contains("Quantidade de bytes efetivamente", content); // 6
        Assert.Contains("RESULTADO:", content);                     // 7
        Assert.Contains("Erro técnico:", content);                  // 8
        Assert.True(File.Exists(path));                             // 9 (a própria exportação)
    }

    public void Dispose()
    {
        _identityProvider.DeleteKeyForTesting();
        _identityProvider.Dispose();
        if (Directory.Exists(_reportsDir)) Directory.Delete(_reportsDir, recursive: true);
    }
}
