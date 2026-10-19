namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// O mecanismo "break-glass" que torna a linha `DocumentContent` da matriz (PermissionMatrix.cs)
/// segura para `SUPER_ADMIN`/`SUPPORT`: ter `PermissionLevel.Read` ali não basta para ler o
/// CONTEÚDO de um documento específico — precisa, além disso, de um grant emitido para
/// exatamente aquele ator e aquele documento, com motivo e prazo. Nunca um grant "para todos os
/// documentos" ou "sem expiração" — ambos os campos são obrigatórios por construção (record,
/// sem construtor sem parâmetros).
/// </summary>
public sealed record DocumentAccessGrant
{
    public required string Id { get; init; }
    public required string GrantedToActorId { get; init; }
    public required string DocumentId { get; init; }

    /// <summary>Nunca vazio — validado em <see cref="IDocumentAccessGrantStore"/>. Vai para o AuditEvent de emissão do grant, não só de uso.</summary>
    public required string Reason { get; init; }

    public required DateTimeOffset IssuedAtUtc { get; init; }
    public required DateTimeOffset ExpiresAtUtc { get; init; }
}

public interface IDocumentAccessGrantStore
{
    /// <summary>Lança <see cref="ArgumentException"/> se <paramref name="grant"/>.Reason for vazio/whitespace, ou se ExpiresAtUtc &lt;= IssuedAtUtc — falha ao emitir, não ao usar.</summary>
    Task IssueAsync(DocumentAccessGrant grant, CancellationToken ct = default);

    /// <summary>Null se não existir grant para este (actorId, documentId), ou se o único existente já expirou em <paramref name="asOfUtc"/>.</summary>
    Task<DocumentAccessGrant?> FindActiveGrantAsync(string actorId, string documentId, DateTimeOffset asOfUtc, CancellationToken ct = default);
}

public sealed class InMemoryDocumentAccessGrantStore : IDocumentAccessGrantStore
{
    private readonly List<DocumentAccessGrant> _grants = [];

    public Task IssueAsync(DocumentAccessGrant grant, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(grant.Reason))
        {
            throw new ArgumentException("DocumentAccessGrant.Reason é obrigatório — nunca um grant sem justificativa registrada.", nameof(grant));
        }

        if (grant.ExpiresAtUtc <= grant.IssuedAtUtc)
        {
            throw new ArgumentException("DocumentAccessGrant.ExpiresAtUtc precisa ser posterior a IssuedAtUtc — nunca um grant sem janela de validade real.", nameof(grant));
        }

        _grants.Add(grant);
        return Task.CompletedTask;
    }

    public Task<DocumentAccessGrant?> FindActiveGrantAsync(string actorId, string documentId, DateTimeOffset asOfUtc, CancellationToken ct = default)
    {
        var match = _grants
            .Where(g => g.GrantedToActorId == actorId && g.DocumentId == documentId && g.ExpiresAtUtc > asOfUtc)
            .OrderByDescending(g => g.IssuedAtUtc)
            .FirstOrDefault();
        return Task.FromResult(match);
    }
}

/// <summary>
/// Índice de documentos SEM filtro de tenant — deliberadamente um TIPO SEPARADO de
/// <see cref="ITenantScopedRepository{Document}"/>, não um "modo especial" dele, para que nenhum
/// outro serviço esbarre nele por engano e ganhe leitura cross-tenant sem querer. Tem exatamente
/// um consumidor: <see cref="DocumentContentAccessService"/>, e só é chamado DEPOIS que um grant
/// válido já foi confirmado — nunca antes.
/// </summary>
public sealed class UnscopedDocumentIndex
{
    private readonly List<Document> _all = [];

    public void Seed(Document document) => _all.Add(document);

    public Task<Document?> FindByIdAnyTenantAsync(string documentId, CancellationToken ct = default) =>
        Task.FromResult(_all.FirstOrDefault(d => d.Id == documentId));
}

public enum DocumentContentAccessOutcome
{
    GrantedOwnTenant,
    GrantedByExplicitGrant,
    DeniedNoGrant,
    DeniedNotFound,
}

public sealed record DocumentContentAccessResult(DocumentContentAccessOutcome Outcome, string? FileName);

/// <summary>
/// Único ponto de leitura de CONTEÚDO de documento do sistema de referência — implementa a regra
/// pedida nesta revisão: "SUPPORT não deve possuir acesso indiscriminado a documentos".
///
/// Dois caminhos, nunca misturados:
/// 1. `TENANT_ADMIN`/`OPERATOR`/`VIEWER` lendo um documento do PRÓPRIO tenant — caminho comum de
///    <see cref="ITenantScopedRepository{Document}"/>, sem grant, porque é o dado deles.
/// 2. `SUPPORT`/`SUPER_ADMIN` (dentro do próprio tenant OU globais) — SEMPRE exigem um
///    <see cref="DocumentAccessGrant"/> válido para aquele documentId específico, mesmo que a
///    <see cref="PermissionMatrix"/> já diga que a role tem `PermissionLevel.Read` em
///    `DocumentContent`. `PermissionLevel.Read` aqui é necessário, nunca suficiente.
///
/// Toda tentativa — concedida ou negada — passa por <see cref="CriticalActionExecutor"/>, então
/// gera AuditEvent sempre, SUPER_ADMIN incluído.
/// </summary>
public sealed class DocumentContentAccessService(
    ITenantScopedRepository<Document> tenantDocuments,
    UnscopedDocumentIndex unscopedIndex,
    IDocumentAccessGrantStore grants,
    CriticalActionExecutor auditExecutor,
    TimeProvider clock)
{
    /// <summary><paramref name="correlationId"/> identifica a requisição que originou esta leitura — ver <see cref="CriticalActionExecutor.ExecuteAsync{T}"/>.</summary>
    public Task<DocumentContentAccessResult> ReadContentAsync(AuthContext ctx, string documentId, string correlationId, CancellationToken ct = default)
    {
        var isOwnTenantRoleWithDirectAccess = ctx.TenantId is not null && ctx.Role is Role.TenantAdmin or Role.Operator or Role.Viewer;

        return auditExecutor.ExecuteAsync(
            ctx,
            AuditAction.DocumentContentAccessed,
            AuditResource.Document,
            documentId,
            correlationId,
            operation: () => isOwnTenantRoleWithDirectAccess
                ? ReadOwnTenantDocumentAsync(ctx, documentId, ct)
                : ReadViaExplicitGrantAsync(ctx, documentId, ct),
            describeMetadata: result => new Dictionary<string, string> { ["decision"] = result.Outcome.ToString() },
            ct: ct);
    }

    private async Task<DocumentContentAccessResult> ReadOwnTenantDocumentAsync(AuthContext ctx, string documentId, CancellationToken ct)
    {
        var doc = await tenantDocuments.GetByIdAsync(ctx, documentId, ct);
        return doc is null
            ? new DocumentContentAccessResult(DocumentContentAccessOutcome.DeniedNotFound, null)
            : new DocumentContentAccessResult(DocumentContentAccessOutcome.GrantedOwnTenant, doc.FileName);
    }

    private async Task<DocumentContentAccessResult> ReadViaExplicitGrantAsync(AuthContext ctx, string documentId, CancellationToken ct)
    {
        // SUPPORT/SUPER_ADMIN nunca leem conteúdo pelo caminho comum, mesmo dentro do próprio
        // tenant (quando SUPPORT é interno de um tenant, AUTHORIZATION.md §8) — sempre precisam
        // do grant, exatamente a garantia que esta revisão pede.
        if (ctx.Role is not (Role.Support or Role.SuperAdmin))
        {
            return new DocumentContentAccessResult(DocumentContentAccessOutcome.DeniedNoGrant, null);
        }

        var grant = await grants.FindActiveGrantAsync(ctx.ActorId, documentId, clock.GetUtcNow(), ct);
        if (grant is null)
        {
            return new DocumentContentAccessResult(DocumentContentAccessOutcome.DeniedNoGrant, null);
        }

        var doc = await unscopedIndex.FindByIdAnyTenantAsync(documentId, ct);
        return doc is null
            ? new DocumentContentAccessResult(DocumentContentAccessOutcome.DeniedNotFound, null)
            : new DocumentContentAccessResult(DocumentContentAccessOutcome.GrantedByExplicitGrant, doc.FileName);
    }
}
