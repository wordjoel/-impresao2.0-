using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.Logging;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace CarrefourPrint.Bridge.Worker;

/// <summary>
/// Equivalente de PrintQueueWorker (Android, CoroutineWorker acionado pelo WorkManager) — uma
/// Task por impressora com job pendente processa em paralelo (impressoras diferentes nunca se
/// bloqueiam entre si; a mesma impressora nunca processa em paralelo consigo mesma, garantido
/// pelo semáforo em ProcessPrintQueueUseCase/PrinterQueueMutexProvider, que é singleton e
/// sobrevive entre execuções deste laço).
///
/// Acorda imediatamente quando InProcessQueueDispatcher sinaliza (job novo enfileirado) e,
/// como rede de segurança, roda de qualquer forma a cada 15 min mesmo sem sinal — equivalente
/// exato de WorkManagerQueueDispatcher.scheduleSafetyNet (Android).
/// </summary>
public sealed class QueueProcessingWorker(
    RecoverOrphanedJobsUseCase recoverOrphanedJobsUseCase,
    ProcessPrintQueueUseCase processPrintQueueUseCase,
    PurgeExpiredHistoryUseCase purgeExpiredHistoryUseCase,
    IPrintJobRepository printJobRepository,
    InProcessQueueDispatcher dispatcher,
    IEventLogger eventLogger) : BackgroundService
{
    private static readonly TimeSpan SafetyNetInterval = TimeSpan.FromMinutes(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            await RunOnceAsync(stoppingToken);
            await dispatcher.WaitForSignalAsync(SafetyNetInterval, stoppingToken);
        }
    }

    private async Task RunOnceAsync(CancellationToken ct)
    {
        eventLogger.Event(LogLevel.Information, "queue_worker_started");
        try
        {
            var recovered = await recoverOrphanedJobsUseCase.InvokeAsync(ct);
            if (recovered > 0)
            {
                eventLogger.Event(LogLevel.Information, "queue_worker_recovered_orphans", extra: new { count = recovered });
            }

            await purgeExpiredHistoryUseCase.InvokeAsync(ct: ct);

            var printerIds = await printJobRepository.PrinterIdsWithPendingJobsAsync(ct);
            var tasks = printerIds.Select(printerId => processPrintQueueUseCase.ProcessPrinterAsync(printerId, ct));
            await Task.WhenAll(tasks);

            eventLogger.Event(LogLevel.Information, "queue_worker_finished");
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            eventLogger.Event(LogLevel.Error, "queue_worker_failed", extra: new { exception = e.Message });
        }
    }
}
