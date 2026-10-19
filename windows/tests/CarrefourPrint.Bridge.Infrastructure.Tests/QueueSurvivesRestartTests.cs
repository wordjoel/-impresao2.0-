using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.Persistence;
using Microsoft.Data.Sqlite;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Prova a exigência explícita "a fila precisa sobreviver a fechamento do aplicativo e
/// reinicialização" com um teste real, não com inspeção de código: usa um arquivo SQLite em
/// disco (nunca :memory:) e simula o encerramento do processo abrindo uma SEGUNDA instância
/// independente de SqliteConnectionFactory/repositório apontando para o MESMO arquivo — nada de
/// estado compartilhado em memória entre as duas "sessões", exatamente como aconteceria entre
/// dois processos reais do Windows Service (um morto, outro iniciado depois).
/// </summary>
public class QueueSurvivesRestartTests : IDisposable
{
    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"carrefour_print_bridge_test_{Guid.NewGuid():N}.db");

    private static Printer TestPrinter() => new()
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

    private static PrintJob ProcessingJob(string id, string printerId) => new()
    {
        Id = id,
        PrinterId = printerId,
        Type = PrintPayloadType.Zpl,
        Payload = "^XA^FDTeste^FS^XZ",
        DocumentName = "etiqueta-orfa",
        Copies = 1,
        Status = JobStatus.Processing, // simula o app morto EXATAMENTE no meio do envio
        Attempts = 1,
        MaxAttempts = 3,
        ErrorMessage = null,
        ExternalDocumentId = null,
        OriginalJobId = null,
        CreatedAt = 1_000,
        StartedAt = 1_500,
        SentAt = null,
        UpdatedAt = 1_500,
    };

    private static PrintJob PendingJob(string id, string printerId, long createdAt) => new()
    {
        Id = id,
        PrinterId = printerId,
        Type = PrintPayloadType.Zpl,
        Payload = "^XA^FDOutraEtiqueta^FS^XZ",
        DocumentName = "etiqueta-pendente",
        Copies = 1,
        Status = JobStatus.Pending,
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
    public async Task Job_stuck_in_processing_is_recovered_to_pending_after_simulated_process_restart()
    {
        // "Sessão 1" — processo original, morre logo após marcar o job como Processing.
        await using (var session1 = OpenNewSession())
        {
            await session1.Printers.UpsertAsync(TestPrinter());
            await session1.Jobs.InsertAsync(ProcessingJob("job-orfao", "printer-1"));
        }
        // Nenhuma referência da "sessão 1" sobrevive daqui em diante — só o arquivo em disco.

        // "Sessão 2" — processo novo, instâncias completamente novas, mesmo arquivo.
        await using var session2 = OpenNewSession();
        var recovered = await new RecoverOrphanedJobsUseCase(session2.Jobs).InvokeAsync();

        Assert.Equal(1, recovered);
        var job = await session2.Jobs.GetByIdAsync("job-orfao");
        Assert.Equal(JobStatus.Pending, job!.Status);

        // E a fila volta a enxergar esse job como o próximo a processar para a impressora — a
        // recuperação não só muda o status, ela efetivamente devolve o job à fila de trabalho.
        var next = await session2.Jobs.NextPendingForAsync("printer-1");
        Assert.Equal("job-orfao", next!.Id);
    }

    [Fact]
    public async Task Pending_job_and_printer_configuration_survive_reopening_the_database_file()
    {
        await using (var session1 = OpenNewSession())
        {
            await session1.Printers.UpsertAsync(TestPrinter());
            await session1.Jobs.InsertAsync(PendingJob("job-1", "printer-1", createdAt: 100));
        }

        await using var session2 = OpenNewSession();
        var printer = await session2.Printers.GetByIdAsync("printer-1");
        var job = await session2.Jobs.GetByIdAsync("job-1");

        Assert.NotNull(printer);
        Assert.Equal("192.168.0.50", printer!.IpAddress);
        Assert.NotNull(job);
        Assert.Equal(JobStatus.Pending, job!.Status);
    }

    [Fact]
    public async Task Fifo_order_is_preserved_across_sessions()
    {
        await using (var session1 = OpenNewSession())
        {
            await session1.Printers.UpsertAsync(TestPrinter());
            await session1.Jobs.InsertAsync(PendingJob("job-newer", "printer-1", createdAt: 200));
            await session1.Jobs.InsertAsync(PendingJob("job-older", "printer-1", createdAt: 100));
        }

        await using var session2 = OpenNewSession();
        var next = await session2.Jobs.NextPendingForAsync("printer-1");

        Assert.Equal("job-older", next!.Id);
    }

    private Session OpenNewSession()
    {
        var factory = new SqliteConnectionFactory(_dbPath);
        factory.EnsureSchema();
        return new Session(new SqlitePrinterRepository(factory), new SqlitePrintJobRepository(factory));
    }

    private sealed record Session(SqlitePrinterRepository Printers, SqlitePrintJobRepository Jobs) : IAsyncDisposable
    {
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    public void Dispose()
    {
        // Microsoft.Data.Sqlite mantém handles nativos em pool mesmo após as conexões serem
        // fechadas — sem isso, o arquivo continua "em uso" e o File.Delete abaixo falha.
        SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var path = _dbPath + suffix;
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
