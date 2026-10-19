namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>Espelha br.com.atos.printbridge.domain.model.AppSettings (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §11 ("Configuration").</summary>
public sealed record AppConfiguration
{
    public int MaxAttempts { get; init; } = 3;
    public int ConnectTimeoutMillis { get; init; } = 5_000;
    public int SocketTimeoutMillis { get; init; } = 8_000;
    public long RetryBaseIntervalMillis { get; init; } = 5_000;
    public bool AutoRetryTransientFailures { get; init; } = true;
    public int HistoryRetentionDays { get; init; } = 30;
    public string? AdminPinHash { get; init; }
}
