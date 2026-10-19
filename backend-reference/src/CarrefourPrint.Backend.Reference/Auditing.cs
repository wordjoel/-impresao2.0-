namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// O tipo de recurso afetado por uma ação auditada — sempre acompanhado de <c>ResourceId</c> em
/// <see cref="AuditEvent"/>/<see cref="GlobalAuditEvent"/>, nunca embutido dentro de uma string de
/// ação (o formato antigo, "DEVICE_REVOKED:device-1", misturava os dois — esta revisão separa).
/// </summary>
public enum AuditResource
{
    Device,
    License,
    Printer,
    PrintJob,
    Document,

    /// <summary>Uma sessão de autenticação — usado por <see cref="AuditAction.AdministrativeLogin"/>, onde o "recurso" é a própria sessão criada, não um recurso de negócio pré-existente.</summary>
    Session,

    Tenant,
    User,
    ApiKey,
}

/// <summary>
/// O catálogo de ações auditáveis. Pedido desta revisão: activation, revocation, license change,
/// printer configuration, job creation, job cancellation, retry administrativo, login
/// administrativo, security event — os nove abaixo, na ordem em que foram pedidos.
/// <see cref="DocumentContentAccessed"/> é anterior a esta revisão (AUTHORIZATION.md §4.1),
/// preservado aqui porque já tinha implementação e testes reais — não descartado só para a lista
/// ficar "redonda".
///
/// Enum extensível por design: uma ação auditável nova de negócio é um novo valor aqui, nunca uma
/// tabela nova nem um novo tipo de evento — mesmo padrão "append-only, enum extensível" já adotado
/// para o `AuditEvent` desde `TENANTS.md` §6.
/// </summary>
public enum AuditAction
{
    DeviceActivated,
    DeviceRevoked,
    LicenseChanged,
    PrinterConfigured,
    PrintJobCreated,
    PrintJobCancelled,
    AdministrativeRetry,
    AdministrativeLogin,
    SecurityEvent,
    DocumentContentAccessed,
}

/// <summary>
/// Metadados NÃO estruturais de um <see cref="AuditEvent"/> — nunca o lugar para o resultado da
/// operação (isso é <see cref="AuditOutcome"/>) nem para relacionar eventos entre si (isso é
/// <c>CorrelationId</c>). Existe só para o mínimo de contexto adicional que uma investigação
/// humana precisaria e que os campos estruturados não cobrem — ex.: quantos seats uma licença
/// passou a ter, qual foi a decisão de negócio de uma tentativa de acesso a documento.
///
/// **A garantia central desta revisão vive aqui:** nenhuma chave cujo nome pareça carregar um
/// segredo é aceita — nem redigida (como o log faz, `CARREFOUR-PRINT-DATA-CLASSIFICATION.md` §4.1),
/// **rejeitada**. Um `AuditEvent` é permanente (nunca editado, `TENANTS.md` §6); persistir para
/// sempre um valor "[REDACTED]" ainda deixaria a FORMA do vazamento gravada eternamente, e uma
/// implementação futura de redação poderia divergir da do log e vazar de verdade. É preferível que
/// o código que monta o evento falhe alto (`ArgumentException`) no exato instante da tentativa, e o
/// desenvolvedor corrija a chamada, do que arriscar isso em texto irreversível.
/// </summary>
public sealed class AuditMetadata
{
    /// <summary>
    /// Mesma lista de substrings já usada para redação de log (`Logger.kt`/`EventLogger.cs`,
    /// `CARREFOUR-PRINT-DATA-CLASSIFICATION.md` §4.1) — consistência deliberada entre as duas
    /// defesas, mesmo com comportamentos diferentes (log redige, auditoria rejeita).
    /// </summary>
    private static readonly string[] ForbiddenKeySubstrings =
    [
        "token", "senha", "password", "secret", "credential", "adminpin",
        "privatekey", "apikey", "hashedsecret",
    ];

    private readonly IReadOnlyDictionary<string, string> _values;

    public static readonly AuditMetadata Empty = new(new Dictionary<string, string>());

    private AuditMetadata(IReadOnlyDictionary<string, string> values) => _values = values;

    /// <summary>Lança <see cref="ArgumentException"/> se qualquer chave contiver (case-insensitive) uma das substrings proibidas — nunca silenciosamente omite ou redige a chave.</summary>
    public static AuditMetadata Of(IReadOnlyDictionary<string, string> values)
    {
        foreach (var key in values.Keys)
        {
            var normalized = key.ToLowerInvariant();
            var match = Array.Find(ForbiddenKeySubstrings, normalized.Contains);
            if (match is not null)
            {
                throw new ArgumentException(
                    $"AuditMetadata rejeitou a chave '{key}' — contém '{match}', que nunca é permitido em metadado de auditoria (pode carregar um segredo). Use um campo estruturado (Resource/ResourceId/CorrelationId) ou remova o dado sensível antes de montar o AuditEvent.",
                    nameof(values));
            }
        }

        return values.Count == 0 ? Empty : new AuditMetadata(new Dictionary<string, string>(values));
    }

    public IReadOnlyDictionary<string, string> Values => _values;
}

/// <summary>
/// TENANTS.md §6 / CARREFOUR-PRINT-DEVICE-IDENTITY.md §2.5, redesenhado nesta revisão para os
/// nove campos pedidos: tenant, ator (user/device — <see cref="ActorId"/>+<see cref="ActorKind"/>),
/// action, resource, resourceId, timestamp, result (<see cref="AuditOutcome"/>), correlationId, e
/// metadata mínima (<see cref="AuditMetadata"/>).
///
/// **Imutável por construção, não por convenção:** todas as propriedades são <c>init</c>-only (uma
/// vez montado, nenhum caminho de código consegue reatribuir um campo — `record with` cria uma
/// instância NOVA, nunca modifica esta) — provado estruturalmente por reflexão em
/// <c>AuditImmutabilityTests</c>. Combinado com <see cref="ITenantScopedRepository{T}"/>, que nunca
/// expõe um método de update/delete para nenhum tipo (também provado por reflexão nos mesmos
/// testes), um `AuditEvent` uma vez gravado não pode ser alterado nem removido por nenhum código
/// deste sistema.
///
/// Sempre com <c>TenantId</c> preenchido — eventos de ator SEM tenant (`SUPER_ADMIN`/`SUPPORT`
/// globais) vão para <see cref="GlobalAuditEvent"/>, nunca aqui (ver <see cref="CriticalActionExecutor"/>).
/// </summary>
public sealed record AuditEvent : ITenantOwned
{
    public required string Id { get; init; }
    public required string TenantId { get; init; }

    /// <summary>Quem fez — pessoa (`ActorKind.User`) ou dispositivo (`ActorKind.Device`), nunca ambíguo (`AuthContext`/`ActorKind`, `AUTHORIZATION.md` §1).</summary>
    public required string ActorId { get; init; }
    public required ActorKind ActorKind { get; init; }

    /// <summary>Só populado quando <see cref="ActorKind"/> é <c>User</c>.</summary>
    public Role? ActorRole { get; init; }

    public required AuditAction Action { get; init; }
    public required AuditResource Resource { get; init; }
    public required string ResourceId { get; init; }
    public required AuditOutcome Outcome { get; init; }

    /// <summary>Amarra este evento a uma operação/requisição específica — nunca gerado silenciosamente por quem grava o evento; ver <see cref="CriticalActionExecutor"/>.</summary>
    public required string CorrelationId { get; init; }

    public required DateTimeOffset OccurredAtUtc { get; init; }

    public AuditMetadata Metadata { get; init; } = AuditMetadata.Empty;
}

/// <summary>
/// Contraparte de <see cref="AuditEvent"/> para atores SEM tenant — hoje só `SUPER_ADMIN` global e
/// `SUPPORT` global (`AUTHORIZATION.md` §3/§8). Deliberadamente um TIPO diferente, não "AuditEvent
/// com TenantId nulo" — a mesma razão estrutural documentada em `AuthContext.cs`: misturar os dois
/// reabriria a possibilidade de um evento global "vazar" para dentro do mecanismo de isolamento por
/// tenant, que assume `TenantId` não-nulo por construção.
///
/// Mesma garantia de imutabilidade de <see cref="AuditEvent"/> (init-only, sem update/delete no
/// armazenamento — <see cref="IGlobalAuditLog"/> só expõe <c>RecordAsync</c>/<c>ListAsync</c>).
/// </summary>
public sealed record GlobalAuditEvent
{
    public required string Id { get; init; }
    public required string ActorId { get; init; }
    public required ActorKind ActorKind { get; init; }
    public Role? ActorRole { get; init; }
    public required AuditAction Action { get; init; }
    public required AuditResource Resource { get; init; }
    public required string ResourceId { get; init; }
    public required AuditOutcome Outcome { get; init; }
    public required string CorrelationId { get; init; }
    public required DateTimeOffset OccurredAtUtc { get; init; }
    public AuditMetadata Metadata { get; init; } = AuditMetadata.Empty;
}
