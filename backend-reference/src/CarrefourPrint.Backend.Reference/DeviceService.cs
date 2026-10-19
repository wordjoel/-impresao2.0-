namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// DTO de requisição de exemplo, carregando de propósito um campo que um cliente mal-intencionado
/// ou mal-implementado poderia tentar usar para se passar por outro tenant — CARREFOUR-PRINT-TENANT-ISOLATION.md §4
/// ("não confiar em tenantId enviado pelo frontend sem validação de identidade/autorização").
/// Nenhum DTO de produção real deveria ter um campo assim; existe aqui só para o teste provar que,
/// mesmo que exista, ele nunca é lido pela camada de serviço.
/// </summary>
public sealed record GetDeviceRequest
{
    public required string DeviceId { get; init; }
    public string? UntrustedRequestedTenantId { get; init; }
}

/// <summary>
/// Camada de serviço de exemplo — a assinatura de <see cref="GetDeviceAsync"/> só aceita
/// <see cref="AuthContext"/> (identidade já verificada) para decidir "de qual tenant"; o campo
/// <see cref="GetDeviceRequest.UntrustedRequestedTenantId"/> nunca é lido neste método,
/// propositalmente — nem por engano, porque não há nenhuma linha de código aqui que o referencie.
/// </summary>
public sealed class DeviceService(ITenantScopedRepository<Device> repository)
{
    public Task<Device?> GetDeviceAsync(AuthContext ctx, GetDeviceRequest request, CancellationToken ct = default) =>
        repository.GetByIdAsync(ctx, request.DeviceId, ct);
}
