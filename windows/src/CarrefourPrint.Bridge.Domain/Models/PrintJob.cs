namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>Espelha br.com.atos.printbridge.domain.model.PrintJob (Android) campo a campo — CARREFOUR-PRINT-CROSS-PLATFORM.md §6.</summary>
public sealed record PrintJob
{
    public const int DefaultMaxAttempts = 3;
    public const int MaxPayloadSizeBytes = 512 * 1024;

    public required string Id { get; init; }
    public required string PrinterId { get; init; }
    public required PrintPayloadType Type { get; init; }
    public required string Payload { get; init; }
    public required string DocumentName { get; init; }
    public required int Copies { get; init; }
    public required JobStatus Status { get; init; }
    public required int Attempts { get; init; }
    public required int MaxAttempts { get; init; }
    public string? ErrorMessage { get; init; }
    public string? ExternalDocumentId { get; init; }
    public string? OriginalJobId { get; init; }
    public required long CreatedAt { get; init; }
    public long? StartedAt { get; init; }
    public long? SentAt { get; init; }
    public required long UpdatedAt { get; init; }
}
