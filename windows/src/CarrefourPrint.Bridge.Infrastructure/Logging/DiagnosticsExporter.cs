using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Infrastructure.Logging;

/// <summary>
/// Porta de br.com.atos.printbridge.util.DiagnosticsExporter (Android) — gera um pacote de
/// diagnóstico local (versão, config técnica sem segredos, últimos eventos) para suporte remoto.
/// Nunca inclui PIN/hash ou payloads ZPL completos.
/// </summary>
public sealed class DiagnosticsExporter(
    IEventLogger eventLogger,
    IDeviceIdentityProvider deviceIdentityProvider,
    string diagnosticsDirectory,
    string appVersion)
{
    public string Export(AppConfiguration settings, bool networkAvailable)
    {
        Directory.CreateDirectory(diagnosticsDirectory);
        var timestamp = DateTime.UtcNow.ToString("yyyyMMdd_HHmmss");
        var path = Path.Combine(diagnosticsDirectory, $"diagnostico_carrefour_print_bridge_windows_{timestamp}.txt");

        var builder = new StringBuilder();
        builder.AppendLine("Carrefour Print Bridge (Windows) — Pacote de Diagnóstico");
        builder.AppendLine($"Gerado em: {DateTime.UtcNow:O}");
        builder.AppendLine();
        builder.AppendLine($"Versão do app: {appVersion}");
        builder.AppendLine($"Device ID: {deviceIdentityProvider.DeviceId()}");
        builder.AppendLine($"Rede disponível: {networkAvailable}");
        builder.AppendLine();
        builder.AppendLine("Configuração técnica:");
        builder.AppendLine($"  maxAttempts={settings.MaxAttempts}");
        builder.AppendLine($"  connectTimeoutMillis={settings.ConnectTimeoutMillis}");
        builder.AppendLine($"  socketTimeoutMillis={settings.SocketTimeoutMillis}");
        builder.AppendLine($"  retryBaseIntervalMillis={settings.RetryBaseIntervalMillis}");
        builder.AppendLine($"  autoRetryTransientFailures={settings.AutoRetryTransientFailures}");
        builder.AppendLine($"  historyRetentionDays={settings.HistoryRetentionDays}");
        builder.AppendLine($"  adminPinConfigured={settings.AdminPinHash is not null}");
        builder.AppendLine();
        builder.AppendLine("Últimos eventos (mais recente por último):");
        foreach (var line in eventLogger.RecentEvents())
        {
            builder.AppendLine($"  {line}");
        }

        File.WriteAllText(path, builder.ToString());
        return path;
    }
}
