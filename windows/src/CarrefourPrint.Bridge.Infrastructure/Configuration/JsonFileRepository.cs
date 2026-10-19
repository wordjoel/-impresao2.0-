using System.Text.Json;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Infrastructure.Configuration;

/// <summary>
/// Porta de br.com.atos.printbridge.data.datastore.SettingsRepositoryImpl (Android, DataStore de
/// Preferences) para um arquivo JSON local no Windows — mesma responsabilidade (persistência
/// local simples, sem servidor), tecnologia equivalente ao alcance do problema (DataStore não
/// existe fora do Android; um arquivo JSON com escrita atômica é o análogo direto no .NET, sem
/// puxar EF Core/SQLite para um único documento de configuração).
///
/// Escrita atômica (escreve em arquivo temporário, depois substitui) evita corromper o arquivo
/// se o processo for encerrado no meio de uma gravação — mesma garantia que o DataStore do
/// Android já dá de fábrica.
/// </summary>
public sealed class JsonFileConfigurationRepository(string filePath) : IConfigurationRepository
{
    private static readonly SemaphoreSlim FileLock = new(1, 1);

    public async Task<AppConfiguration> CurrentAsync(CancellationToken ct = default)
    {
        await FileLock.WaitAsync(ct);
        try
        {
            return await ReadAsync(ct);
        }
        finally
        {
            FileLock.Release();
        }
    }

    public async Task UpdateAsync(Func<AppConfiguration, AppConfiguration> transform, CancellationToken ct = default)
    {
        await FileLock.WaitAsync(ct);
        try
        {
            var current = await ReadAsync(ct);
            var next = transform(current);
            await WriteAsync(next, ct);
        }
        finally
        {
            FileLock.Release();
        }
    }

    private async Task<AppConfiguration> ReadAsync(CancellationToken ct)
    {
        if (!File.Exists(filePath)) return new AppConfiguration();
        await using var stream = File.OpenRead(filePath);
        return await JsonSerializer.DeserializeAsync(stream, BridgeJsonContext.Default.AppConfiguration, ct)
            ?? new AppConfiguration();
    }

    private async Task WriteAsync(AppConfiguration value, CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(filePath)!);
        var tempPath = filePath + ".tmp";
        try
        {
            await using (var stream = File.Create(tempPath))
            {
                await JsonSerializer.SerializeAsync(stream, value, BridgeJsonContext.Default.AppConfiguration, ct);
            }
            File.Move(tempPath, filePath, overwrite: true);
        }
        catch
        {
            // Sem isso, um processo encerrado/uma exceção entre a criação do .tmp e o rename
            // deixaria um arquivo temporário órfão para sempre no diretório de dados
            // (CARREFOUR-PRINT-WINDOWS-HARDENING.md §3) — best-effort, nunca mascara a exceção original.
            try { File.Delete(tempPath); } catch { /* best-effort */ }
            throw;
        }
    }
}

/// <summary>
/// Mesmo padrão de JsonFileConfigurationRepository, para LicenseState. Este arquivo continua tão
/// editável por qualquer usuário com acesso ao disco quanto qualquer outro arquivo local — isso
/// deixou de ser um problema porque o único campo persistido (RawToken) é um token assinado pelo
/// servidor: editar o arquivo só produz um token com assinatura inválida, que
/// EvaluateLicenseStatusUseCase rejeita (Restricted), nunca um valor forjado aceito como válido.
/// Ver CARREFOUR-PRINT-SERVER-LICENSING.md §5.
/// </summary>
public sealed class JsonFileLicenseRepository(string filePath) : ILicenseRepository
{
    private static readonly SemaphoreSlim FileLock = new(1, 1);

    public async Task<LicenseState> CurrentAsync(CancellationToken ct = default)
    {
        await FileLock.WaitAsync(ct);
        try
        {
            return await ReadAsync(ct);
        }
        finally
        {
            FileLock.Release();
        }
    }

    public async Task UpdateAsync(Func<LicenseState, LicenseState> transform, CancellationToken ct = default)
    {
        await FileLock.WaitAsync(ct);
        try
        {
            var current = await ReadAsync(ct);
            var next = transform(current);
            await WriteAsync(next, ct);
        }
        finally
        {
            FileLock.Release();
        }
    }

    private async Task<LicenseState> ReadAsync(CancellationToken ct)
    {
        if (!File.Exists(filePath)) return new LicenseState();
        await using var stream = File.OpenRead(filePath);
        return await JsonSerializer.DeserializeAsync(stream, BridgeJsonContext.Default.LicenseState, ct)
            ?? new LicenseState();
    }

    private async Task WriteAsync(LicenseState value, CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(filePath)!);
        var tempPath = filePath + ".tmp";
        try
        {
            await using (var stream = File.Create(tempPath))
            {
                await JsonSerializer.SerializeAsync(stream, value, BridgeJsonContext.Default.LicenseState, ct);
            }
            File.Move(tempPath, filePath, overwrite: true);
        }
        catch
        {
            // Sem isso, um processo encerrado/uma exceção entre a criação do .tmp e o rename
            // deixaria um arquivo temporário órfão (CONFIDENTIAL, carrega o License Token) para
            // sempre no diretório de dados — best-effort, nunca mascara a exceção original.
            try { File.Delete(tempPath); } catch { /* best-effort */ }
            throw;
        }
    }
}
