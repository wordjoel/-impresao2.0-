namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>Espelha o contrato DeviceIdentity de CARREFOUR-PRINT-CROSS-PLATFORM.md §2.</summary>
public sealed record DeviceIdentity
{
    public required string DeviceId { get; init; }
    public required string Platform { get; init; } = "WINDOWS";

    /// <summary>Base64 de X.509 SubjectPublicKeyInfo (DER), curva P-256.</summary>
    public required string PublicKey { get; init; }

    public required string KeyAlgorithm { get; init; } = "EC_P256";
    public required string AppVersion { get; init; }
    public required long CreatedAt { get; init; }
}
