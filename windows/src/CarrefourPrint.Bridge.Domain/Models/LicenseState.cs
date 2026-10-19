namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>
/// Estado de uma License no backend — CARREFOUR-PRINT-SERVER-LICENSING.md §3. Os cinco estados
/// exigidos pelo usuário; PENDING é o estado inicial (licença criada, nenhum device ativado
/// ainda), REVOKED é terminal (mesma regra de TENANTS.md/DEVICE-IDENTITY.md — reativar é sempre
/// uma License nova, nunca a mesma revertida).
/// </summary>
public enum LicenseTokenStatus
{
    Pending,
    Active,
    Suspended,
    Expired,
    Revoked,
}

/// <summary>
/// Conteúdo verificado de um License Token — só existe como este tipo depois de passar por
/// ILicenseTokenVerifier.TryVerify. Nunca construído diretamente a partir de dado não verificado
/// (CARREFOUR-PRINT-SERVER-LICENSING.md §5).
/// </summary>
public sealed record LicenseTokenPayload
{
    public required string DeviceId { get; init; }
    public required string LicenseId { get; init; }
    public required string TenantId { get; init; }
    public required LicenseTokenStatus Status { get; init; }
    public required long IssuedAt { get; init; }
    public required long ValidUntil { get; init; }
    public required long MaxOfflineMillis { get; init; }
}

/// <summary>
/// Estado local persistido — deliberadamente reduzido a UM campo. Antes desta versão, o estado
/// local carregava campos livres (status, lastValidatedAt) que o próprio app escrevia em si
/// mesmo — nada impedia editar o arquivo/DataStore local para forjar "status=ACTIVE". Agora o
/// único dado persistido é o token assinado pelo servidor; todo o resto (status, validade) só
/// existe depois de verificado via ILicenseTokenVerifier — nunca lido de um campo livre.
/// Ver CARREFOUR-PRINT-SERVER-LICENSING.md §5 para a motivação completa desta mudança.
/// </summary>
public sealed record LicenseState
{
    /// <summary>Token compacto "payload.assinatura" (Base64Url), emitido na ativação e renovado a cada heartbeat. Null = nunca ativado.</summary>
    public string? RawToken { get; init; }
}
