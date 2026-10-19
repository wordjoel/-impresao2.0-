using CarrefourPrint.Bridge.Domain.Abstractions;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Aplica `AppConfiguration.HistoryRetentionDays` de verdade — antes desta versão, o valor
/// existia no modelo de configuração mas `PurgeHistoryOlderThanAsync` nunca era chamado de lugar
/// nenhum no Windows Bridge. Chamado a cada tick do QueueProcessingWorker, então o histórico
/// nunca cresce sem limite entre uma configuração e a próxima (CARREFOUR-PRINT-DATA-CLASSIFICATION.md
/// — retenção de payloads).
/// </summary>
public sealed class PurgeExpiredHistoryUseCase(
    IPrintJobRepository printJobRepository,
    IConfigurationRepository configurationRepository)
{
    public async Task InvokeAsync(long? nowMillis = null, CancellationToken ct = default)
    {
        var now = nowMillis ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var settings = await configurationRepository.CurrentAsync(ct);
        if (settings.HistoryRetentionDays <= 0) return; // 0 ou negativo = retenção desabilitada, nunca purga automaticamente
        var cutoff = now - settings.HistoryRetentionDays * 24L * 60 * 60 * 1000;
        await printJobRepository.PurgeHistoryOlderThanAsync(cutoff, ct);
    }
}
