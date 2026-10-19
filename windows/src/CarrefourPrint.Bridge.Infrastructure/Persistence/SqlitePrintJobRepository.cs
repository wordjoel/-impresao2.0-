using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using Microsoft.Data.Sqlite;

namespace CarrefourPrint.Bridge.Infrastructure.Persistence;

/// <summary>
/// Porta 1:1 de PrintJobDao + PrintJobRepositoryImpl (Android/Room) — mesmas queries, mesma
/// ordenação FIFO (createdAt ASC), mesmos filtros de status. É este arquivo SQLite em disco (não
/// em memória) que garante a sobrevivência da fila a fechamento do app, reinicialização do
/// Windows, queda de rede e impressora indisponível (CARREFOUR-PRINT-CROSS-PLATFORM.md §16 regra 3)
/// — o job PENDING/PROCESSING persiste no arquivo independentemente do processo estar vivo.
/// </summary>
public sealed class SqlitePrintJobRepository(SqliteConnectionFactory factory) : IPrintJobRepository
{
    public async Task<IReadOnlyList<PrintJob>> GetActiveAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM print_jobs WHERE status IN ('PENDING', 'PROCESSING') ORDER BY createdAt ASC";
        return await ReadAllAsync(cmd, ct);
    }

    public async Task<IReadOnlyList<PrintJob>> GetHistoryAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM print_jobs WHERE status IN ('SENT', 'FAILED', 'CANCELLED') ORDER BY updatedAt DESC";
        return await ReadAllAsync(cmd, ct);
    }

    public async Task<PrintJob?> GetByIdAsync(string id, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM print_jobs WHERE id = $id LIMIT 1";
        cmd.Parameters.AddWithValue("$id", id);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        return await reader.ReadAsync(ct) ? Read(reader) : null;
    }

    public async Task InsertAsync(PrintJob job, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO print_jobs (id, printerId, type, payload, documentName, copies, status, attempts, maxAttempts,
                                     errorMessage, externalDocumentId, originalJobId, createdAt, startedAt, sentAt, updatedAt)
            VALUES ($id, $printerId, $type, $payload, $documentName, $copies, $status, $attempts, $maxAttempts,
                    $errorMessage, $externalDocumentId, $originalJobId, $createdAt, $startedAt, $sentAt, $updatedAt)
            ON CONFLICT(id) DO UPDATE SET
                printerId=excluded.printerId, type=excluded.type, payload=excluded.payload,
                documentName=excluded.documentName, copies=excluded.copies, status=excluded.status,
                attempts=excluded.attempts, maxAttempts=excluded.maxAttempts, errorMessage=excluded.errorMessage,
                externalDocumentId=excluded.externalDocumentId, originalJobId=excluded.originalJobId,
                createdAt=excluded.createdAt, startedAt=excluded.startedAt, sentAt=excluded.sentAt, updatedAt=excluded.updatedAt
            """;
        Bind(cmd, job);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task UpdateAsync(PrintJob job, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            UPDATE print_jobs SET printerId=$printerId, type=$type, payload=$payload, documentName=$documentName,
                copies=$copies, status=$status, attempts=$attempts, maxAttempts=$maxAttempts, errorMessage=$errorMessage,
                externalDocumentId=$externalDocumentId, originalJobId=$originalJobId, createdAt=$createdAt,
                startedAt=$startedAt, sentAt=$sentAt, updatedAt=$updatedAt
            WHERE id = $id
            """;
        Bind(cmd, job);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task<PrintJob?> NextPendingForAsync(string printerId, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            SELECT * FROM print_jobs WHERE printerId = $printerId AND status = 'PENDING'
            ORDER BY createdAt ASC LIMIT 1
            """;
        cmd.Parameters.AddWithValue("$printerId", printerId);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        return await reader.ReadAsync(ct) ? Read(reader) : null;
    }

    public async Task<IReadOnlyList<PrintJob>> FindOrphanedProcessingAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM print_jobs WHERE status = 'PROCESSING'";
        return await ReadAllAsync(cmd, ct);
    }

    public async Task CancelAsync(string id, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "UPDATE print_jobs SET status = 'CANCELLED', updatedAt = $now WHERE id = $id AND status IN ('PENDING', 'FAILED')";
        cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        cmd.Parameters.AddWithValue("$id", id);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task PurgeHistoryOlderThanAsync(long timestampMillis, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "DELETE FROM print_jobs WHERE status IN ('SENT', 'FAILED', 'CANCELLED') AND updatedAt < $timestamp";
        cmd.Parameters.AddWithValue("$timestamp", timestampMillis);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task<IReadOnlyList<string>> PrinterIdsWithPendingJobsAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT DISTINCT printerId FROM print_jobs WHERE status = 'PENDING'";
        var results = new List<string>();
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        while (await reader.ReadAsync(ct)) results.Add(reader.GetString(0));
        return results;
    }

    private static void Bind(SqliteCommand cmd, PrintJob job)
    {
        cmd.Parameters.AddWithValue("$id", job.Id);
        cmd.Parameters.AddWithValue("$printerId", job.PrinterId);
        cmd.Parameters.AddWithValue("$type", job.Type.ToWire());
        cmd.Parameters.AddWithValue("$payload", job.Payload);
        cmd.Parameters.AddWithValue("$documentName", job.DocumentName);
        cmd.Parameters.AddWithValue("$copies", job.Copies);
        cmd.Parameters.AddWithValue("$status", job.Status.ToWire());
        cmd.Parameters.AddWithValue("$attempts", job.Attempts);
        cmd.Parameters.AddWithValue("$maxAttempts", job.MaxAttempts);
        cmd.Parameters.AddWithValue("$errorMessage", (object?)job.ErrorMessage ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$externalDocumentId", (object?)job.ExternalDocumentId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$originalJobId", (object?)job.OriginalJobId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$createdAt", job.CreatedAt);
        cmd.Parameters.AddWithValue("$startedAt", (object?)job.StartedAt ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$sentAt", (object?)job.SentAt ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$updatedAt", job.UpdatedAt);
    }

    private static async Task<IReadOnlyList<PrintJob>> ReadAllAsync(SqliteCommand cmd, CancellationToken ct)
    {
        var results = new List<PrintJob>();
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        while (await reader.ReadAsync(ct)) results.Add(Read(reader));
        return results;
    }

    private static PrintJob Read(SqliteDataReader reader) => new()
    {
        Id = reader.GetString(reader.GetOrdinal("id")),
        PrinterId = reader.GetString(reader.GetOrdinal("printerId")),
        Type = EnumMapping.PayloadTypeFromWire(reader.GetString(reader.GetOrdinal("type"))),
        Payload = reader.GetString(reader.GetOrdinal("payload")),
        DocumentName = reader.GetString(reader.GetOrdinal("documentName")),
        Copies = reader.GetInt32(reader.GetOrdinal("copies")),
        Status = EnumMapping.JobStatusFromWire(reader.GetString(reader.GetOrdinal("status"))),
        Attempts = reader.GetInt32(reader.GetOrdinal("attempts")),
        MaxAttempts = reader.GetInt32(reader.GetOrdinal("maxAttempts")),
        ErrorMessage = reader.IsDBNull(reader.GetOrdinal("errorMessage")) ? null : reader.GetString(reader.GetOrdinal("errorMessage")),
        ExternalDocumentId = reader.IsDBNull(reader.GetOrdinal("externalDocumentId")) ? null : reader.GetString(reader.GetOrdinal("externalDocumentId")),
        OriginalJobId = reader.IsDBNull(reader.GetOrdinal("originalJobId")) ? null : reader.GetString(reader.GetOrdinal("originalJobId")),
        CreatedAt = reader.GetInt64(reader.GetOrdinal("createdAt")),
        StartedAt = reader.IsDBNull(reader.GetOrdinal("startedAt")) ? null : reader.GetInt64(reader.GetOrdinal("startedAt")),
        SentAt = reader.IsDBNull(reader.GetOrdinal("sentAt")) ? null : reader.GetInt64(reader.GetOrdinal("sentAt")),
        UpdatedAt = reader.GetInt64(reader.GetOrdinal("updatedAt")),
    };
}
