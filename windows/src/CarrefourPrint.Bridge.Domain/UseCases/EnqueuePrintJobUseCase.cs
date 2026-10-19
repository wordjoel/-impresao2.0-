using System.Collections.Concurrent;
using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Errors;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Espelha br.com.atos.printbridge.application.usecase.DuplicateSubmissionGuard (Android):
/// protege contra envio duplicado por cliques/chamadas repetidas dentro de uma janela curta.
/// Guarda em memória apenas — reinicia a cada processo, aceitável pois o risco real é o
/// duplo-toque/duplo-request do chamador, não duplicidade entre execuções do serviço.
/// </summary>
public sealed class DuplicateSubmissionGuard
{
    private sealed record Entry(long TimestampMillis, PrintJob Job);

    private readonly ConcurrentDictionary<string, Entry> _lastSubmission = new();
    private readonly long _windowMillis;

    public DuplicateSubmissionGuard(long windowMillis = 2_000)
    {
        _windowMillis = windowMillis;
    }

    public PrintJob? PreviousIfDuplicate(string key)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (_lastSubmission.TryGetValue(key, out var last) && now - last.TimestampMillis < _windowMillis)
        {
            return last.Job;
        }
        return null;
    }

    public void Remember(string key, PrintJob job) =>
        _lastSubmission[key] = new Entry(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), job);
}

/// <summary>
/// Espelha br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase (Android) 1:1,
/// incluindo o portão de licenciamento (CARREFOUR-PRINT-SERVER-LICENSING.md §10). Deliberadamente
/// NÃO usado por ProcessPrintQueueUseCase/QueueProcessingWorker, RetryPrintJobUseCase, nem
/// RecoverOrphanedJobsUseCase — um job já persistido antes de a licença entrar em Restricted
/// continua sendo propriedade da fila local até terminar; só a criação de trabalho NOVO é gateada.
/// </summary>
public sealed class EnqueuePrintJobUseCase(
    IPrintJobRepository printJobRepository,
    IPrinterRepository printerRepository,
    DuplicateSubmissionGuard duplicateGuard,
    IQueueDispatcher queueDispatcher,
    EvaluateLicenseStatusUseCase evaluateLicenseStatus)
{
    public async Task<PrintJob> InvokeAsync(
        string? printerId,
        PrintPayloadType type,
        string payload,
        string documentName,
        int copies = 1,
        string? externalDocumentId = null,
        int maxAttempts = PrintJob.DefaultMaxAttempts,
        CancellationToken ct = default)
    {
        var licenseStatus = await evaluateLicenseStatus.InvokeAsync(ct: ct);
        if (licenseStatus != EffectiveLicenseStatus.Operating)
        {
            throw new LicenseRestrictedException(licenseStatus.ToString());
        }

        if (string.IsNullOrWhiteSpace(payload)) throw new EmptyPayloadException();

        var payloadBytes = Encoding.UTF8.GetByteCount(payload);
        if (payloadBytes > PrintJob.MaxPayloadSizeBytes)
        {
            throw new PayloadTooLargeException(payloadBytes, PrintJob.MaxPayloadSizeBytes);
        }

        var targetPrinter = printerId is not null
            ? await printerRepository.GetByIdAsync(printerId, ct)
            : await printerRepository.GetDefaultAsync(ct);
        if (targetPrinter is null) throw new NoDefaultPrinterException();

        var debounceKey = $"{targetPrinter.Id}:{documentName}:{payload.GetHashCode()}";
        var previous = duplicateGuard.PreviousIfDuplicate(debounceKey);
        if (previous is not null) return previous;

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var job = new PrintJob
        {
            Id = Guid.NewGuid().ToString(),
            PrinterId = targetPrinter.Id,
            Type = type,
            Payload = payload,
            DocumentName = string.IsNullOrWhiteSpace(documentName) ? $"documento-{now}" : documentName,
            Copies = Math.Max(copies, 1),
            Status = JobStatus.Pending,
            Attempts = 0,
            MaxAttempts = Math.Max(maxAttempts, 1),
            ErrorMessage = null,
            ExternalDocumentId = externalDocumentId,
            OriginalJobId = null,
            CreatedAt = now,
            StartedAt = null,
            SentAt = null,
            UpdatedAt = now,
        };

        duplicateGuard.Remember(debounceKey, job);
        await printJobRepository.InsertAsync(job, ct);
        queueDispatcher.RequestProcessing();
        return job;
    }
}

/// <summary>Espelha ReprintJobUseCase (Android) — sujeito ao mesmo portão de licença de <see cref="EnqueuePrintJobUseCase"/>, pela mesma razão: cria uma linha nova, não continua a antiga.</summary>
public sealed class ReprintJobUseCase(IPrintJobRepository printJobRepository, IQueueDispatcher queueDispatcher, EvaluateLicenseStatusUseCase evaluateLicenseStatus)
{
    public async Task<PrintJob> InvokeAsync(string originalJobId, CancellationToken ct = default)
    {
        var licenseStatus = await evaluateLicenseStatus.InvokeAsync(ct: ct);
        if (licenseStatus != EffectiveLicenseStatus.Operating)
        {
            throw new LicenseRestrictedException(licenseStatus.ToString());
        }

        var original = await printJobRepository.GetByIdAsync(originalJobId, ct)
            ?? throw new PrintJobNotFoundException(originalJobId);

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var newJob = original with
        {
            Id = Guid.NewGuid().ToString(),
            Status = JobStatus.Pending,
            Attempts = 0,
            ErrorMessage = null,
            OriginalJobId = original.Id,
            CreatedAt = now,
            StartedAt = null,
            SentAt = null,
            UpdatedAt = now,
        };

        await printJobRepository.InsertAsync(newJob, ct);
        queueDispatcher.RequestProcessing();
        return newJob;
    }
}

/// <summary>Espelha CancelPrintJobUseCase (Android).</summary>
public sealed class CancelPrintJobUseCase(IPrintJobRepository printJobRepository)
{
    public Task InvokeAsync(string jobId, CancellationToken ct = default) =>
        printJobRepository.CancelAsync(jobId, ct);
}

/// <summary>Espelha RetryPrintJobUseCase (Android).</summary>
public sealed class RetryPrintJobUseCase(IPrintJobRepository printJobRepository, IQueueDispatcher queueDispatcher)
{
    public async Task InvokeAsync(string jobId, CancellationToken ct = default)
    {
        var job = await printJobRepository.GetByIdAsync(jobId, ct)
            ?? throw new PrintJobNotFoundException(jobId);

        await printJobRepository.UpdateAsync(
            job with { Status = JobStatus.Pending, Attempts = 0, ErrorMessage = null, UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() },
            ct);
        queueDispatcher.RequestProcessing();
    }
}

/// <summary>Espelha RecoverOrphanedJobsUseCase (Android) — jobs presos em Processing (serviço morto/reiniciado em meio ao envio) voltam a Pending no boot. Ver CARREFOUR-PRINT-CROSS-PLATFORM.md §16 regra 3 e a exigência de sobrevivência a reinicialização.</summary>
public sealed class RecoverOrphanedJobsUseCase(IPrintJobRepository printJobRepository)
{
    public async Task<int> InvokeAsync(CancellationToken ct = default)
    {
        var orphaned = await printJobRepository.FindOrphanedProcessingAsync(ct);
        foreach (var job in orphaned)
        {
            await printJobRepository.UpdateAsync(
                job with { Status = JobStatus.Pending, UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() },
                ct);
        }
        return orphaned.Count;
    }
}
