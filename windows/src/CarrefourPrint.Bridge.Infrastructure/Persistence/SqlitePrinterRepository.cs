using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using Microsoft.Data.Sqlite;

namespace CarrefourPrint.Bridge.Infrastructure.Persistence;

/// <summary>Porta 1:1 de PrinterDao + PrinterRepositoryImpl (Android/Room) para Microsoft.Data.Sqlite.</summary>
public sealed class SqlitePrinterRepository(SqliteConnectionFactory factory) : IPrinterRepository
{
    public async Task<IReadOnlyList<Printer>> GetAllAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM printers ORDER BY name ASC";
        var results = new List<Printer>();
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        while (await reader.ReadAsync(ct)) results.Add(Read(reader));
        return results;
    }

    public async Task<Printer?> GetByIdAsync(string id, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM printers WHERE id = $id LIMIT 1";
        cmd.Parameters.AddWithValue("$id", id);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        return await reader.ReadAsync(ct) ? Read(reader) : null;
    }

    public async Task<Printer?> GetDefaultAsync(CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT * FROM printers WHERE isDefault = 1 LIMIT 1";
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        return await reader.ReadAsync(ct) ? Read(reader) : null;
    }

    public async Task UpsertAsync(Printer printer, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using (var cmd = connection.CreateCommand())
        {
            cmd.CommandText = """
                INSERT INTO printers (id, name, model, ipAddress, port, protocol, language, isDefault, enabled, createdAt, updatedAt)
                VALUES ($id, $name, $model, $ip, $port, $protocol, $language, $isDefault, $enabled, $createdAt, $updatedAt)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name, model = excluded.model, ipAddress = excluded.ipAddress,
                    port = excluded.port, protocol = excluded.protocol, language = excluded.language,
                    isDefault = excluded.isDefault, enabled = excluded.enabled,
                    createdAt = excluded.createdAt, updatedAt = excluded.updatedAt
                """;
            cmd.Parameters.AddWithValue("$id", printer.Id);
            cmd.Parameters.AddWithValue("$name", printer.Name);
            cmd.Parameters.AddWithValue("$model", printer.Model.ToWire());
            cmd.Parameters.AddWithValue("$ip", printer.IpAddress);
            cmd.Parameters.AddWithValue("$port", printer.Port);
            cmd.Parameters.AddWithValue("$protocol", printer.Protocol.ToWire());
            cmd.Parameters.AddWithValue("$language", printer.Language.ToWire());
            cmd.Parameters.AddWithValue("$isDefault", printer.IsDefault ? 1 : 0);
            cmd.Parameters.AddWithValue("$enabled", printer.Enabled ? 1 : 0);
            cmd.Parameters.AddWithValue("$createdAt", printer.CreatedAt);
            cmd.Parameters.AddWithValue("$updatedAt", printer.UpdatedAt);
            await cmd.ExecuteNonQueryAsync(ct);
        }

        if (printer.IsDefault)
        {
            using var clearCmd = connection.CreateCommand();
            clearCmd.CommandText = "UPDATE printers SET isDefault = 0 WHERE id != $id";
            clearCmd.Parameters.AddWithValue("$id", printer.Id);
            await clearCmd.ExecuteNonQueryAsync(ct);
        }
    }

    public async Task DeleteAsync(string id, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "DELETE FROM printers WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task SetDefaultAsync(string id, CancellationToken ct = default)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        using var connection = factory.Open();
        using (var clearCmd = connection.CreateCommand())
        {
            clearCmd.CommandText = "UPDATE printers SET isDefault = 0 WHERE id != $id";
            clearCmd.Parameters.AddWithValue("$id", id);
            await clearCmd.ExecuteNonQueryAsync(ct);
        }
        using (var markCmd = connection.CreateCommand())
        {
            markCmd.CommandText = "UPDATE printers SET isDefault = 1, updatedAt = $now WHERE id = $id";
            markCmd.Parameters.AddWithValue("$now", now);
            markCmd.Parameters.AddWithValue("$id", id);
            await markCmd.ExecuteNonQueryAsync(ct);
        }
    }

    public async Task SetEnabledAsync(string id, bool enabled, CancellationToken ct = default)
    {
        using var connection = factory.Open();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "UPDATE printers SET enabled = $enabled, updatedAt = $now WHERE id = $id";
        cmd.Parameters.AddWithValue("$enabled", enabled ? 1 : 0);
        cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        cmd.Parameters.AddWithValue("$id", id);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    private static Printer Read(SqliteDataReader reader) => new()
    {
        Id = reader.GetString(reader.GetOrdinal("id")),
        Name = reader.GetString(reader.GetOrdinal("name")),
        Model = EnumMapping.ModelFromWire(reader.GetString(reader.GetOrdinal("model"))),
        IpAddress = reader.GetString(reader.GetOrdinal("ipAddress")),
        Port = reader.GetInt32(reader.GetOrdinal("port")),
        Protocol = EnumMapping.ProtocolFromWire(reader.GetString(reader.GetOrdinal("protocol"))),
        Language = EnumMapping.LanguageFromWire(reader.GetString(reader.GetOrdinal("language"))),
        IsDefault = reader.GetInt64(reader.GetOrdinal("isDefault")) != 0,
        Enabled = reader.GetInt64(reader.GetOrdinal("enabled")) != 0,
        CreatedAt = reader.GetInt64(reader.GetOrdinal("createdAt")),
        UpdatedAt = reader.GetInt64(reader.GetOrdinal("updatedAt")),
    };
}
