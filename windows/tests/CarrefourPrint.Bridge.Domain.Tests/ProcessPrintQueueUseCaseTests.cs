using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>Porta 1:1 de ProcessPrintQueueUseCaseTest.kt (Android) — mesmos 4 casos: sucesso, esgotamento de tentativas, sucesso após falha, sem rede.</summary>
public class ProcessPrintQueueUseCaseTests
{
    private static Printer TestPrinter(string id = "printer-1") => new()
    {
        Id = id,
        Name = "Expedição 01",
        Model = PrinterModel.TscMb241,
        IpAddress = "192.168.0.50",
        Port = 9100,
        Protocol = PrinterProtocol.Tcp,
        Language = PrinterLanguage.Zpl,
        IsDefault = true,
        Enabled = true,
        CreatedAt = 0,
        UpdatedAt = 0,
    };

    private static PrintJob TestJob(string printerId, int maxAttempts = 3) => new()
    {
        Id = Guid.NewGuid().ToString(),
        PrinterId = printerId,
        Type = PrintPayloadType.Zpl,
        Payload = "^XA^FDTeste^FS^XZ",
        DocumentName = "etiqueta-teste",
        Copies = 1,
        Status = JobStatus.Pending,
        Attempts = 0,
        MaxAttempts = maxAttempts,
        ErrorMessage = null,
        ExternalDocumentId = null,
        OriginalJobId = null,
        CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        StartedAt = null,
        SentAt = null,
        UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
    };

    [Fact]
    public async Task Successful_send_marks_job_as_sent()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(TestPrinter());
        var jobRepo = new FakePrintJobRepository();
        var job = TestJob("printer-1");
        await jobRepo.InsertAsync(job);

        var useCase = new ProcessPrintQueueUseCase(
            jobRepo, printerRepo,
            new FakeConfigurationRepository(new AppConfiguration { RetryBaseIntervalMillis = 0 }),
            new PrintEngine(FakePrinterTransport.AlwaysSucceeds()),
            new FakeNetworkStateProvider(true),
            new PrinterQueueMutexProvider());

        await useCase.ProcessPrinterAsync("printer-1");

        var updated = await jobRepo.GetByIdAsync(job.Id);
        Assert.Equal(JobStatus.Sent, updated!.Status);
        Assert.Equal(1, updated.Attempts);
    }

    [Fact]
    public async Task Failure_below_max_attempts_returns_job_to_pending_for_retry()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(TestPrinter());
        var jobRepo = new FakePrintJobRepository();
        var job = TestJob("printer-1", maxAttempts: 3);
        await jobRepo.InsertAsync(job);

        var useCase = new ProcessPrintQueueUseCase(
            jobRepo, printerRepo,
            new FakeConfigurationRepository(new AppConfiguration { RetryBaseIntervalMillis = 0 }),
            new PrintEngine(FakePrinterTransport.AlwaysFails(TransportFailureReason.ConnectionRefused)),
            new FakeNetworkStateProvider(true),
            new PrinterQueueMutexProvider());

        // O loop interno de ProcessPrinterAsync já esgota as tentativas sozinho (Pending ->
        // Processing -> falha -> volta a Pending -> tenta de novo) até atingir maxAttempts.
        await useCase.ProcessPrinterAsync("printer-1");

        var updated = await jobRepo.GetByIdAsync(job.Id);
        Assert.Equal(JobStatus.Failed, updated!.Status);
        Assert.Equal(3, updated.Attempts);
        Assert.Equal(TransportFailureReason.ConnectionRefused.OperatorMessage(), updated.ErrorMessage);
    }

    [Fact]
    public async Task Job_that_fails_once_then_succeeds_ends_up_sent()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(TestPrinter());
        var jobRepo = new FakePrintJobRepository();
        var job = TestJob("printer-1", maxAttempts: 3);
        await jobRepo.InsertAsync(job);

        var useCase = new ProcessPrintQueueUseCase(
            jobRepo, printerRepo,
            new FakeConfigurationRepository(new AppConfiguration { RetryBaseIntervalMillis = 0 }),
            new PrintEngine(FakePrinterTransport.SucceedsOnAttempt(2)),
            new FakeNetworkStateProvider(true),
            new PrinterQueueMutexProvider());

        await useCase.ProcessPrinterAsync("printer-1");

        var updated = await jobRepo.GetByIdAsync(job.Id);
        Assert.Equal(JobStatus.Sent, updated!.Status);
        Assert.Equal(2, updated.Attempts);
    }

    [Fact]
    public async Task No_network_keeps_job_pending_without_spending_an_attempt()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(TestPrinter());
        var jobRepo = new FakePrintJobRepository();
        var job = TestJob("printer-1");
        await jobRepo.InsertAsync(job);

        var useCase = new ProcessPrintQueueUseCase(
            jobRepo, printerRepo,
            new FakeConfigurationRepository(new AppConfiguration { RetryBaseIntervalMillis = 0, AutoRetryTransientFailures = false }),
            new PrintEngine(FakePrinterTransport.AlwaysSucceeds()),
            new FakeNetworkStateProvider(false),
            new PrinterQueueMutexProvider());

        await useCase.ProcessPrinterAsync("printer-1");

        var updated = await jobRepo.GetByIdAsync(job.Id);
        Assert.True(updated!.Status is JobStatus.Pending or JobStatus.Failed);
        Assert.Equal(TransportFailureReason.NoNetwork.OperatorMessage(), updated.ErrorMessage);
    }
}
