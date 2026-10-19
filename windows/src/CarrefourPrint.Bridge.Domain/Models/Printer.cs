namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>Espelha br.com.atos.printbridge.domain.model.Printer (Android) campo a campo — CARREFOUR-PRINT-CROSS-PLATFORM.md §5.</summary>
public sealed record Printer
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required PrinterModel Model { get; init; }
    public required string IpAddress { get; init; }
    public required int Port { get; init; }
    public required PrinterProtocol Protocol { get; init; }
    public required PrinterLanguage Language { get; init; }
    public required bool IsDefault { get; init; }
    public required bool Enabled { get; init; }
    public required long CreatedAt { get; init; }
    public required long UpdatedAt { get; init; }
}
