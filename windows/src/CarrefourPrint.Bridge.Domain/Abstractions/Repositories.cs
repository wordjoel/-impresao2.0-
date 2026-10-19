using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.Abstractions;

/// <summary>
/// Espelha br.com.atos.printbridge.domain.repository.PrinterRepository (Android). O Android expõe
/// observeAll() como Flow para a UI Compose reativa; o Windows Bridge v1 não tem UI própria
/// (ver CARREFOUR-PRINT-TARGET-ARCHITECTURE.md §4 — "UI mínima", não implementada nesta primeira
/// versão), então GetAllAsync() retorna um snapshot — evita depender de System.Reactive só para
/// uma capacidade sem consumidor real hoje. Se uma UI/CLI interativa for adicionada depois, um
/// método de observação pode ser acrescentado então, sem quebrar este contrato.
/// </summary>
public interface IPrinterRepository
{
    Task<IReadOnlyList<Printer>> GetAllAsync(CancellationToken ct = default);
    Task<Printer?> GetByIdAsync(string id, CancellationToken ct = default);
    Task<Printer?> GetDefaultAsync(CancellationToken ct = default);
    Task UpsertAsync(Printer printer, CancellationToken ct = default);
    Task DeleteAsync(string id, CancellationToken ct = default);
    Task SetDefaultAsync(string id, CancellationToken ct = default);
    Task SetEnabledAsync(string id, bool enabled, CancellationToken ct = default);
}

/// <summary>Espelha br.com.atos.printbridge.domain.repository.PrintJobRepository (Android) — ver nota de IPrinterRepository sobre snapshot vs. observação.</summary>
public interface IPrintJobRepository
{
    Task<IReadOnlyList<PrintJob>> GetActiveAsync(CancellationToken ct = default);
    Task<IReadOnlyList<PrintJob>> GetHistoryAsync(CancellationToken ct = default);
    Task<PrintJob?> GetByIdAsync(string id, CancellationToken ct = default);
    Task InsertAsync(PrintJob job, CancellationToken ct = default);
    Task UpdateAsync(PrintJob job, CancellationToken ct = default);
    Task<PrintJob?> NextPendingForAsync(string printerId, CancellationToken ct = default);
    Task<IReadOnlyList<PrintJob>> FindOrphanedProcessingAsync(CancellationToken ct = default);
    Task CancelAsync(string id, CancellationToken ct = default);
    Task PurgeHistoryOlderThanAsync(long timestampMillis, CancellationToken ct = default);
    Task<IReadOnlyList<string>> PrinterIdsWithPendingJobsAsync(CancellationToken ct = default);
}

/// <summary>Espelha br.com.atos.printbridge.domain.repository.SettingsRepository (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §11.</summary>
public interface IConfigurationRepository
{
    Task<AppConfiguration> CurrentAsync(CancellationToken ct = default);
    Task UpdateAsync(Func<AppConfiguration, AppConfiguration> transform, CancellationToken ct = default);
}

/// <summary>Espelha br.com.atos.printbridge.domain.repository.LicenseRepository (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §4.</summary>
public interface ILicenseRepository
{
    Task<LicenseState> CurrentAsync(CancellationToken ct = default);
    Task UpdateAsync(Func<LicenseState, LicenseState> transform, CancellationToken ct = default);
}

/// <summary>
/// Verifica um License Token compacto ("payload.assinatura", Base64Url) contra a chave pública
/// do servidor embutida no app — nunca contra uma chave obtida em runtime (isso permitiria a um
/// atacante em MITM substituir a chave e forjar tokens). Pura função criptográfica, sem estado,
/// sem I/O — testável sem qualquer infraestrutura. CARREFOUR-PRINT-SERVER-LICENSING.md §5.
/// </summary>
public interface ILicenseTokenVerifier
{
    bool TryVerify(string compactToken, out LicenseTokenPayload? payload);
}
