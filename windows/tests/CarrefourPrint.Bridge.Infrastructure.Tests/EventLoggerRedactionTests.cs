using CarrefourPrint.Bridge.Infrastructure.Logging;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Prova automatizada da salvaguarda de código de redação de segredos do EventLogger
/// (CARREFOUR-PRINT-DATA-CLASSIFICATION.md §4 / CARREFOUR-CYBER-SECURITY-GATE.md H3): qualquer
/// propriedade de `extra` cujo nome bata com SensitiveKeyPattern
/// (token|senha|password|secret|credential|adminpin|licensetoken|devicetoken|rawtoken) tem o valor
/// substituído por "[REDACTED]" antes de entrar no ring buffer — ou seja, antes de poder ser
/// exportado pelo DiagnosticsExporter ou escrito no Windows Event Log. Um valor sensível que vaze
/// em RecentEvents() falha aqui.
/// </summary>
public class EventLoggerRedactionTests
{
    private static EventLogger CreateLogger(RecentEventsBuffer buffer) =>
        new(NullLogger<EventLogger>.Instance, buffer);

    private static string Capture(Action<EventLogger> emit)
    {
        var buffer = new RecentEventsBuffer();
        emit(CreateLogger(buffer));
        return buffer.RecentEvents().Single();
    }

    [Fact]
    public void Property_matching_the_sensitive_pattern_has_its_value_redacted()
    {
        var line = Capture(logger => logger.Event(
            LogLevel.Information,
            "test_redaction",
            extra: new
            {
                licenseToken = "payload.assinatura.sensivel",
                adminPin = "1234",
                password = "super-secreto",
                rawToken = "x.y.z",
                deviceToken = "token-de-dispositivo",
                credential = "credencial-crua",
            }));

        foreach (var secret in new[] { "payload.assinatura.sensivel", "1234", "super-secreto", "x.y.z", "token-de-dispositivo", "credencial-crua" })
        {
            Assert.DoesNotContain(secret, line);
        }
        Assert.Contains("[REDACTED]", line);
    }

    [Fact]
    public void Property_not_matching_the_sensitive_pattern_keeps_its_value()
    {
        var line = Capture(logger => logger.Event(
            LogLevel.Information,
            "test_redaction",
            extra: new
            {
                documentName = "etiqueta.txt",
                sizeBytes = 42,
                printerId = "printer-1",
            }));

        Assert.Contains("etiqueta.txt", line);
        Assert.Contains("42", line);
        Assert.Contains("printer-1", line);
        Assert.DoesNotContain("[REDACTED]", line);
    }

    [Fact]
    public void Redaction_matches_property_name_case_insensitively()
    {
        var line = Capture(logger => logger.Event(
            LogLevel.Warning,
            "test_redaction",
            extra: new
            {
                LicenseToken = "valor-com-maiusculas",
                DEVICETOKEN = "outro-segredo",
            }));

        Assert.DoesNotContain("valor-com-maiusculas", line);
        Assert.DoesNotContain("outro-segredo", line);
        Assert.Contains("[REDACTED]", line);
    }

    [Fact]
    public void Payload_content_stays_legible_in_diagnostics()
    {
        // Redação é por CHAVE do campo, não por conteúdo — payload ZPL/nome de documento seguem
        // legíveis no diagnóstico, exatamente como no Android (LoggerRedactionTest).
        var line = Capture(logger => logger.Event(
            LogLevel.Error,
            "test_redaction",
            extra: new { payload = "^XA^FDTeste^FS^XZ" }));

        Assert.Contains("^XA^FDTeste^FS^XZ", line);
        Assert.DoesNotContain("[REDACTED]", line);
    }
}