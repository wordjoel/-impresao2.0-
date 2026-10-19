namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Espelha as três identidades nunca-misturadas de CARREFOUR-PRINT-TOKEN-DESIGN.md §0.
/// </summary>
public enum ActorKind
{
    User,
    Device,
    ApiKey,
}

/// <summary>
/// A ÚNICA fonte de verdade de "qual tenant" para qualquer operação — CARREFOUR-PRINT-TENANT-ISOLATION.md §1.
///
/// <see cref="TenantId"/> é NULÁVEL de propósito — corrige uma lacuna real da versão anterior
/// deste tipo (exigia tenantId sempre), que não representava corretamente `SUPER_ADMIN` nem
/// `SUPPORT` global (AUTHORIZATION.md §3: "SUPER_ADMIN é o único papel sem tenantId associado").
/// Um ator com `TenantId = null` **nunca** casa com nenhum recurso em
/// <see cref="ITenantScopedRepository{T}"/> (o filtro `item.TenantId == ctx.TenantId` nunca é
/// verdadeiro para um TenantId de entidade real comparado a null) — ou seja, o caminho comum de
/// consulta já falha fechado para atores globais por construção. Acesso cross-tenant legítimo
/// (ex.: SUPPORT investigando um tenant específico) precisa de um caminho explícito e diferente,
/// nunca do mesmo método usado para "meu próprio tenant" — ver `DocumentContentAccessService`
/// para o único caso onde isso hoje é necessário e foi implementado.
///
/// Deliberadamente sem construtor público sem verificação: em produção, isto só é construído
/// pelo middleware de autenticação, a partir de um token JÁ VERIFICADO — nunca a partir de um
/// campo de corpo/query/header não verificado da requisição.
/// </summary>
public sealed record AuthContext
{
    public string? TenantId { get; init; }
    public required string ActorId { get; init; }
    public required ActorKind Kind { get; init; }

    /// <summary>Só populado para ActorKind.User — role do RBAC (AUTHORIZATION.md §3).</summary>
    public Role? Role { get; init; }

    public static AuthContext ForUser(string tenantId, string userId, Role role) =>
        new() { TenantId = tenantId, ActorId = userId, Kind = ActorKind.User, Role = role };

    /// <summary>SUPER_ADMIN nunca tem tenantId — AUTHORIZATION.md §3.</summary>
    public static AuthContext ForSuperAdmin(string userId) =>
        new() { TenantId = null, ActorId = userId, Kind = ActorKind.User, Role = Reference.Role.SuperAdmin };

    /// <summary>SUPPORT da equipe da plataforma (não de um tenant específico) — AUTHORIZATION.md §8.</summary>
    public static AuthContext ForGlobalSupport(string userId) =>
        new() { TenantId = null, ActorId = userId, Kind = ActorKind.User, Role = Reference.Role.Support };

    public static AuthContext ForDevice(string tenantId, string deviceId) =>
        new() { TenantId = tenantId, ActorId = deviceId, Kind = ActorKind.Device };

    public static AuthContext ForApiKey(string tenantId, string apiKeyId) =>
        new() { TenantId = tenantId, ActorId = apiKeyId, Kind = ActorKind.ApiKey };
}
