using System.Collections.Concurrent;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>Um SemaphoreSlim(1,1) por impressora garante que nunca haja dois envios TCP simultâneos para o mesmo IP — equivalente exato de PrinterQueueMutexProvider (Android, Kotlin Mutex).</summary>
public sealed class PrinterQueueMutexProvider
{
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _semaphores = new();

    public SemaphoreSlim SemaphoreFor(string printerId) =>
        _semaphores.GetOrAdd(printerId, _ => new SemaphoreSlim(1, 1));
}

/// <summary>
/// Espelha br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase (Android) 1:1 —
/// mesma fila FIFO por impressora, mesmo backoff linear, mesmo hard-limit por maxAttempts (nunca
/// loop infinito). Impressoras diferentes são processadas de forma independente pelo chamador
/// (uma Task por printerId); dentro da mesma impressora, o semáforo garante ordem sequencial estrita.
/// </summary>
public sealed class ProcessPrintQueueUseCase(
    IPrintJobRepository printJobRepository,
    IPrinterRepository printerRepository,
    IConfigurationRepository configurationRepository,
    PrintEngine printEngine,
    INetworkStateProvider networkStateProvider,
    PrinterQueueMutexProvider mutexProvider)
{
    public async Task ProcessPrinterAsync(string printerId, CancellationToken ct = default)
    {
        var semaphore = mutexProvider.SemaphoreFor(printerId);
        await semaphore.WaitAsync(ct);
        try
        {
            while (true)
            {
                var job = await printJobRepository.NextPendingForAsync(printerId, ct);
                if (job is null) break;

                var printer = await printerRepository.GetByIdAsync(printerId, ct);
                if (printer is null) break;
                if (!printer.Enabled) break;

                if (!networkStateProvider.IsNetworkAvailable())
                {
                    // Sem rede afeta TODOS os jobs pendentes desta impressora igualmente — encerra
                    // o processamento desta execução em vez de continuar em loop apertado sem
                    // nunca avançar. O job permanece recuperável: o tick periódico do Worker tenta de novo.
                    await HandleFailureAsync(job, TransportFailureReason.NoNetwork, "Sem rede no dispositivo Windows", ct);
                    break;
                }

                var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                await printJobRepository.UpdateAsync(
                    job with { Status = JobStatus.Processing, StartedAt = now, Attempts = job.Attempts + 1, UpdatedAt = now },
                    ct);

                var result = await printEngine.SendAsync(printer, job with { Attempts = job.Attempts + 1 }, ct);

                switch (result)
                {
                    case TransportResult.Success:
                        var sentAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                        await printJobRepository.UpdateAsync(
                            job with { Status = JobStatus.Sent, Attempts = job.Attempts + 1, ErrorMessage = null, SentAt = sentAt, UpdatedAt = sentAt },
                            ct);
                        break;
                    case TransportResult.Failure failure:
                        await HandleFailureAsync(job with { Attempts = job.Attempts + 1 }, failure.Reason, failure.TechnicalMessage, ct);
                        break;
                }
            }
        }
        finally
        {
            semaphore.Release();
        }
    }

    private async Task HandleFailureAsync(PrintJob job, TransportFailureReason reason, string technicalMessage, CancellationToken ct)
    {
        var settings = await configurationRepository.CurrentAsync(ct);
        var exhausted = job.Attempts >= job.MaxAttempts;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var finalStatus = exhausted ? JobStatus.Failed : JobStatus.Pending;

        await printJobRepository.UpdateAsync(
            job with { Status = finalStatus, ErrorMessage = reason.OperatorMessage(), UpdatedAt = now },
            ct);

        if (!exhausted && settings.AutoRetryTransientFailures)
        {
            var backoff = settings.RetryBaseIntervalMillis * job.Attempts;
            await Task.Delay(TimeSpan.FromMilliseconds(backoff), ct);
        }
    }
}
