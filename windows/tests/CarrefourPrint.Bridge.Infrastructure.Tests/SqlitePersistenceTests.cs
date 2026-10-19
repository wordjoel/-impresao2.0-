using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>Porta 1:1 de AppDatabaseTest.kt (Android/Room) para Microsoft.Data.Sqlite — mesmas 5 verificações, contra SQLite real (arquivo em disco), não contra fake.</summary>
public class SqlitePersistenceTests : IDisposable
{
    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"carrefour_print_bridge_test_{Guid.NewGuid():N}.db");
    private readonly SqliteConnectionFactory _factory;
    private readonly SqlitePrinterRepository _printers;
    private readonly SqlitePrintJobRepository _jobs;

    public SqlitePersistenceTests()
    {
        _factory = new SqliteConnectionFactory(_dbPath);
        _factory.EnsureSchema();
        _printers = new SqlitePrinterRepository(_factory);
        _jobs = new SqlitePrintJobRepository(_factory);
    }

    private static Printer Printer(string id, bool isDefault = false) => new()
    {
        Id = id,
        Name = "Expedição 01",
        Model = PrinterModel.TscMb241,
        IpAddress = "192.168.0.50",
        Port = 9100,
        Protocol = PrinterProtocol.Tcp,
        Language = PrinterLanguage.Zpl,
        IsDefault = isDefault,
        Enabled = true,
        CreatedAt = 0,
        UpdatedAt = 0,
    };

    private static PrintJob Job(string id, string printerId, JobStatus status, long createdAt = 0) => new()
    {
        Id = id,
        PrinterId = printerId,
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
        CreatedAt = createdAt,
        StartedAt = null,
        SentAt = null,
        UpdatedAt = createdAt,
    };

    [Fact]
    public async Task Only_one_printer_can_be_default_at_a_time()
    {
        await _printers.UpsertAsync(Printer("p1", isDefault: true));
        await _printers.UpsertAsync(Printer("p2", isDefault: false));

        await _printers.SetDefaultAsync("p2");

        var defaultPrinter = await _printers.GetDefaultAsync();
        Assert.Equal("p2", defaultPrinter!.Id);
    }

    [Fact]
    public async Task Next_pending_for_returns_oldest_pending_job_for_that_printer_only()
    {
        await _printers.UpsertAsync(Printer("p1"));
        await _jobs.InsertAsync(Job("job-newer", "p1", JobStatus.Pending, createdAt: 200));
        await _jobs.InsertAsync(Job("job-older", "p1", JobStatus.Pending, createdAt: 100));
        await _jobs.InsertAsync(Job("job-other-printer", "p2", JobStatus.Pending, createdAt: 50));
        await _jobs.InsertAsync(Job("job-sent", "p1", JobStatus.Sent, createdAt: 10));

        var next = await _jobs.NextPendingForAsync("p1");

        Assert.Equal("job-older", next!.Id);
    }

    [Fact]
    public async Task Active_and_history_queries_partition_by_status_correctly()
    {
        await _printers.UpsertAsync(Printer("p1"));
        await _jobs.InsertAsync(Job("pending-job", "p1", JobStatus.Pending));
        await _jobs.InsertAsync(Job("processing-job", "p1", JobStatus.Processing));
        await _jobs.InsertAsync(Job("sent-job", "p1", JobStatus.Sent));
        await _jobs.InsertAsync(Job("failed-job", "p1", JobStatus.Failed));

        var active = await _jobs.GetActiveAsync();
        var history = await _jobs.GetHistoryAsync();

        Assert.Equal(["pending-job", "processing-job"], active.Select(j => j.Id).OrderBy(x => x));
        Assert.Equal(["failed-job", "sent-job"], history.Select(j => j.Id).OrderBy(x => x));
    }

    [Fact]
    public async Task Cancel_only_affects_pending_or_failed_jobs()
    {
        await _printers.UpsertAsync(Printer("p1"));
        await _jobs.InsertAsync(Job("pending-job", "p1", JobStatus.Pending));
        await _jobs.InsertAsync(Job("processing-job", "p1", JobStatus.Processing));

        await _jobs.CancelAsync("pending-job");
        await _jobs.CancelAsync("processing-job");

        Assert.Equal(JobStatus.Cancelled, (await _jobs.GetByIdAsync("pending-job"))!.Status);
        Assert.Equal(JobStatus.Processing, (await _jobs.GetByIdAsync("processing-job"))!.Status);
    }

    [Fact]
    public async Task Deleting_a_printer_does_not_require_deleting_its_jobs()
    {
        await _printers.UpsertAsync(Printer("p1"));
        await _jobs.InsertAsync(Job("job-1", "p1", JobStatus.Sent));

        await _printers.DeleteAsync("p1");

        Assert.Null(await _printers.GetByIdAsync("p1"));
        Assert.NotNull(await _jobs.GetByIdAsync("job-1"));
    }

    public void Dispose()
    {
        // Microsoft.Data.Sqlite mantém handles nativos em pool mesmo após Dispose() das conexões
        // — sem isso, o arquivo continua "em uso" e o File.Delete abaixo falha com IOException.
        SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var path = _dbPath + suffix;
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
