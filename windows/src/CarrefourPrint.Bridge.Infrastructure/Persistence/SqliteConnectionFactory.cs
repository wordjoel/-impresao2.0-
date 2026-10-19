using Microsoft.Data.Sqlite;

namespace CarrefourPrint.Bridge.Infrastructure.Persistence;

/// <summary>
/// Equivalente de AppDatabase (Room, Android) — Microsoft.Data.Sqlite é o provedor
/// Microsoft-suportado de SQLite para .NET (mesmo motor de banco do Room, o que preserva a
/// semântica de arquivo único em disco que sobrevive a fechamento do app/serviço e a
/// reinicialização do Windows). WAL habilitado para tolerar leitura concorrente com escrita sem
/// bloquear o processo inteiro.
/// </summary>
public sealed class SqliteConnectionFactory(string databasePath)
{
    public const string SchemaVersion = "1";

    private const string SchemaSql = """
        CREATE TABLE IF NOT EXISTS printers (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            model TEXT NOT NULL,
            ipAddress TEXT NOT NULL,
            port INTEGER NOT NULL,
            protocol TEXT NOT NULL,
            language TEXT NOT NULL,
            isDefault INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS print_jobs (
            id TEXT PRIMARY KEY NOT NULL,
            printerId TEXT NOT NULL,
            type TEXT NOT NULL,
            payload TEXT NOT NULL,
            documentName TEXT NOT NULL,
            copies INTEGER NOT NULL,
            status TEXT NOT NULL,
            attempts INTEGER NOT NULL,
            maxAttempts INTEGER NOT NULL,
            errorMessage TEXT NULL,
            externalDocumentId TEXT NULL,
            originalJobId TEXT NULL,
            createdAt INTEGER NOT NULL,
            startedAt INTEGER NULL,
            sentAt INTEGER NULL,
            updatedAt INTEGER NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_print_jobs_printer_status ON print_jobs(printerId, status);
        CREATE INDEX IF NOT EXISTS idx_print_jobs_status ON print_jobs(status);
        """;

    public string DatabasePath => databasePath;

    public SqliteConnection Open()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
        var connection = new SqliteConnection($"Data Source={databasePath}");
        connection.Open();
        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;";
            pragma.ExecuteNonQuery();
        }
        return connection;
    }

    public void EnsureSchema()
    {
        using var connection = Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = SchemaSql;
        cmd.ExecuteNonQuery();
    }
}
