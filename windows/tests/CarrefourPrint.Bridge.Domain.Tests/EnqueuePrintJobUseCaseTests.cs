using CarrefourPrint.Bridge.Domain.Errors;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>Porta 1:1 de EnqueuePrintJobUseCaseTest.kt (Android) — mesmos 4 casos, mesmas asserções.</summary>
public class EnqueuePrintJobUseCaseTests
{
    private static readonly Printer DefaultPrinter = new()
    {
        Id = "printer-1",
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

    private static (EnqueuePrintJobUseCase UseCase, FakePrintJobRepository JobRepo, FakeQueueDispatcher Dispatcher) BuildUseCase(
        FakePrinterRepository? printerRepo = null,
        LicenseTokenStatus licenseStatus = LicenseTokenStatus.Active,
        long? licenseValidUntil = null)
    {
        printerRepo ??= new FakePrinterRepository();
        var jobRepo = new FakePrintJobRepository();
        var dispatcher = new FakeQueueDispatcher();
        var evaluateLicenseStatus = new EvaluateLicenseStatusUseCase(
            new FakeLicenseRepository(new LicenseState
            {
                RawToken = TestLicenseTokenSigner.Sign(status: licenseStatus, validUntil: licenseValidUntil ?? long.MaxValue / 2),
            }),
            TestLicenseTokenSigner.CreateVerifier());
        var useCase = new EnqueuePrintJobUseCase(jobRepo, printerRepo, new DuplicateSubmissionGuard(), dispatcher, evaluateLicenseStatus);
        return (useCase, jobRepo, dispatcher);
    }

    [Fact]
    public async Task Valid_job_is_enqueued_as_pending_using_default_printer()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var (useCase, jobRepo, dispatcher) = BuildUseCase(printerRepo);

        var job = await useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^FDTeste^FS^XZ", "etiqueta-1");

        Assert.Equal(JobStatus.Pending, job.Status);
        Assert.Equal(DefaultPrinter.Id, job.PrinterId);
        Assert.Single(jobRepo.Snapshot);
        Assert.Equal(1, dispatcher.RequestCount);
    }

    [Fact]
    public async Task Blank_payload_is_rejected_before_reaching_the_repository()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var (useCase, jobRepo, _) = BuildUseCase(printerRepo);

        await Assert.ThrowsAsync<EmptyPayloadException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, "   ", "doc"));
        Assert.Empty(jobRepo.Snapshot);
    }

    [Fact]
    public async Task Payload_above_limit_is_rejected()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var (useCase, _, _) = BuildUseCase(printerRepo);
        var oversized = new string('A', PrintJob.MaxPayloadSizeBytes + 1);

        await Assert.ThrowsAsync<PayloadTooLargeException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, oversized, "doc"));
    }

    [Fact]
    public async Task No_default_printer_and_none_specified_throws()
    {
        var (useCase, _, _) = BuildUseCase(new FakePrinterRepository());

        await Assert.ThrowsAsync<NoDefaultPrinterException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc"));
    }

    // ---------- Portão de licença (CARREFOUR-PRINT-SERVER-LICENSING.md §10) ----------

    [Fact]
    public async Task New_job_is_rejected_when_license_grace_period_has_expired()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var (useCase, jobRepo, dispatcher) = BuildUseCase(printerRepo, licenseValidUntil: 1L);

        var ex = await Assert.ThrowsAsync<LicenseRestrictedException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc"));

        Assert.Equal("Restricted", ex.EffectiveStatus);
        Assert.Empty(jobRepo.Snapshot);
        Assert.Equal(0, dispatcher.RequestCount);
    }

    [Fact]
    public async Task New_job_is_rejected_when_license_is_suspended()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var (useCase, jobRepo, _) = BuildUseCase(printerRepo, licenseStatus: LicenseTokenStatus.Suspended);

        await Assert.ThrowsAsync<LicenseRestrictedException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc"));
        Assert.Empty(jobRepo.Snapshot);
    }

    [Fact]
    public async Task New_job_is_rejected_when_device_was_never_activated()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var jobRepo = new FakePrintJobRepository();
        var evaluateLicenseStatus = new EvaluateLicenseStatusUseCase(new FakeLicenseRepository(), TestLicenseTokenSigner.CreateVerifier());
        var useCase = new EnqueuePrintJobUseCase(jobRepo, printerRepo, new DuplicateSubmissionGuard(), new FakeQueueDispatcher(), evaluateLicenseStatus);

        var ex = await Assert.ThrowsAsync<LicenseRestrictedException>(() => useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc"));

        Assert.Equal("NotActivated", ex.EffectiveStatus);
        Assert.Empty(jobRepo.Snapshot);
    }

    [Fact]
    public async Task New_job_is_accepted_while_still_within_the_offline_grace_window()
    {
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var futureValidUntil = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 72L * 60 * 60 * 1000;
        var (useCase, jobRepo, _) = BuildUseCase(printerRepo, licenseValidUntil: futureValidUntil);

        var job = await useCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc");

        Assert.Equal(JobStatus.Pending, job.Status);
        Assert.Single(jobRepo.Snapshot);
    }

    [Fact]
    public async Task A_job_accepted_while_operating_is_never_destroyed_and_can_still_be_retried_after_the_license_becomes_restricted()
    {
        // Regra central de CARREFOUR-PRINT-SERVER-LICENSING.md §10: o portão só existe em
        // EnqueuePrintJobUseCase/ReprintJobUseCase. RetryPrintJobUseCase nunca recebeu (nem deve
        // receber) uma dependência de licença — trava de regressão contra acoplamento futuro.
        var printerRepo = new FakePrinterRepository();
        printerRepo.Seed(DefaultPrinter);
        var futureValidUntil = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 1000;
        var (enqueueUseCase, jobRepo, _) = BuildUseCase(printerRepo, licenseValidUntil: futureValidUntil);
        var job = await enqueueUseCase.InvokeAsync(null, PrintPayloadType.Zpl, "^XA^XZ", "doc");
        await jobRepo.UpdateAsync(job with { Status = JobStatus.Failed, Attempts = 1 });

        Assert.Single(jobRepo.Snapshot);
        Assert.Equal(JobStatus.Failed, jobRepo.Snapshot.Single().Status);

        var retryUseCase = new RetryPrintJobUseCase(jobRepo, new FakeQueueDispatcher());
        await retryUseCase.InvokeAsync(job.Id); // nunca lança, mesmo com a licença (hipoteticamente) Restricted agora

        Assert.Single(jobRepo.Snapshot); // nunca destruído
        Assert.Equal(JobStatus.Pending, jobRepo.Snapshot.Single().Status); // retomado normalmente
    }
}
