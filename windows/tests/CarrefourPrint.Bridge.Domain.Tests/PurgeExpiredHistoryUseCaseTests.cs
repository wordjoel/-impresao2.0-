using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

public class PurgeExpiredHistoryUseCaseTests
{
    private const long OneDayMillis = 24L * 60 * 60 * 1000;

    private static PrintJob Job(string id, JobStatus status, long updatedAt) => new()
    {
        Id = id,
        PrinterId = "printer-1",
        Type = PrintPayloadType.Zpl,
        Payload = "^XA^XZ",
        DocumentName = "etiqueta",
        Copies = 1,
        Status = status,
        Attempts = 0,
        MaxAttempts = 3,
        ErrorMessage = null,
        ExternalDocumentId = null,
        OriginalJobId = null,
        CreatedAt = updatedAt,
        StartedAt = null,
        SentAt = null,
        UpdatedAt = updatedAt,
    };

    [Fact]
    public async Task Purges_history_older_than_the_configured_retention_window()
    {
        const long now = 100_000_000L;
        var jobRepo = new FakePrintJobRepository();
        await jobRepo.InsertAsync(Job("old-sent", JobStatus.Sent, now - 31 * OneDayMillis));
        await jobRepo.InsertAsync(Job("recent-sent", JobStatus.Sent, now - 1 * OneDayMillis));
        await jobRepo.InsertAsync(Job("old-pending", JobStatus.Pending, now - 31 * OneDayMillis));

        var settingsRepo = new FakeConfigurationRepository(new AppConfiguration { HistoryRetentionDays = 30 });
        var useCase = new PurgeExpiredHistoryUseCase(jobRepo, settingsRepo);

        await useCase.InvokeAsync(now);

        Assert.DoesNotContain(jobRepo.Snapshot, j => j.Id == "old-sent");
        Assert.Contains(jobRepo.Snapshot, j => j.Id == "recent-sent");
        // Job ativo (Pending) nunca é purgado, mesmo antigo — fila continua sendo autoridade.
        Assert.Contains(jobRepo.Snapshot, j => j.Id == "old-pending");
    }

    [Fact]
    public async Task Retention_of_zero_or_less_disables_automatic_purge()
    {
        const long now = 100_000_000L;
        var jobRepo = new FakePrintJobRepository();
        await jobRepo.InsertAsync(Job("very-old-sent", JobStatus.Sent, now - 3650 * OneDayMillis));

        var settingsRepo = new FakeConfigurationRepository(new AppConfiguration { HistoryRetentionDays = 0 });
        var useCase = new PurgeExpiredHistoryUseCase(jobRepo, settingsRepo);

        await useCase.InvokeAsync(now);

        Assert.Single(jobRepo.Snapshot);
    }
}
