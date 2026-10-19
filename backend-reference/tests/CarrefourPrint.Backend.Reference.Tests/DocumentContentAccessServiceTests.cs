namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova a regra pedida nesta revisão: "SUPPORT não deve possuir acesso indiscriminado a
/// documentos". `SUPER_ADMIN` está sujeito à mesma regra — a matriz (AUTHORIZATION.md §4) já
/// marcava a leitura de SUPER_ADMIN como "com auditoria"; aqui isso vira "com grant + auditoria".
/// </summary>
public class DocumentContentAccessServiceTests
{
    private const string TenantA = "tenant-a";
    private const string CorrelationId = "corr-doc-access";

    private sealed record Fixture(
        DocumentContentAccessService Service,
        ITenantScopedRepository<Document> TenantDocuments,
        UnscopedDocumentIndex UnscopedIndex,
        IDocumentAccessGrantStore Grants,
        ITenantScopedRepository<AuditEvent> TenantAudit,
        IGlobalAuditLog GlobalAudit);

    private static Fixture NewFixture()
    {
        var tenantDocuments = new InMemoryTenantScopedRepository<Document>();
        var unscopedIndex = new UnscopedDocumentIndex();
        var grants = new InMemoryDocumentAccessGrantStore();
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = new CriticalActionExecutor(tenantAudit, globalAudit, TimeProvider.System);
        var service = new DocumentContentAccessService(tenantDocuments, unscopedIndex, grants, executor, TimeProvider.System);
        return new Fixture(service, tenantDocuments, unscopedIndex, grants, tenantAudit, globalAudit);
    }

    [Fact]
    public async Task Tenant_admin_reads_own_tenant_document_directly_without_grant()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);
        await f.TenantDocuments.InsertAsync(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));

        var result = await f.Service.ReadContentAsync(ctx, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.GrantedOwnTenant, result.Outcome);
        Assert.Equal("nota-fiscal-a.pdf", result.FileName);
    }

    [Fact]
    public async Task Support_is_denied_document_content_without_a_grant_even_within_its_own_tenant()
    {
        var f = NewFixture();
        // SUPPORT interno de um tenant específico (AUTHORIZATION.md §8) — mesmo assim, sem
        // acesso indiscriminado ao conteúdo dos documentos DAQUELE MESMO tenant.
        var supportInTenant = AuthContext.ForUser(TenantA, "support-1", Role.Support);
        await f.TenantDocuments.InsertAsync(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));

        var result = await f.Service.ReadContentAsync(supportInTenant, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNoGrant, result.Outcome);
        Assert.Null(result.FileName);
    }

    [Fact]
    public async Task Global_super_admin_is_denied_document_content_without_a_grant()
    {
        var f = NewFixture();
        var superAdmin = AuthContext.ForSuperAdmin("root-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));

        var result = await f.Service.ReadContentAsync(superAdmin, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNoGrant, result.Outcome);
    }

    [Fact]
    public async Task Support_can_read_content_once_a_valid_grant_for_that_exact_document_exists()
    {
        var f = NewFixture();
        var support = AuthContext.ForGlobalSupport("support-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        await f.Grants.IssueAsync(new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1",
            Reason = "Ticket #4821 - cliente reporta valor divergente na nota",
            IssuedAtUtc = DateTimeOffset.UtcNow,
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(1),
        });

        var result = await f.Service.ReadContentAsync(support, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.GrantedByExplicitGrant, result.Outcome);
        Assert.Equal("nota-fiscal-a.pdf", result.FileName);
    }

    [Fact]
    public async Task Expired_grant_denies_access_exactly_like_no_grant_at_all()
    {
        var f = NewFixture();
        var support = AuthContext.ForGlobalSupport("support-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        await f.Grants.IssueAsync(new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1",
            Reason = "Investigação encerrada ontem",
            IssuedAtUtc = DateTimeOffset.UtcNow.AddDays(-2),
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddDays(-1),
        });

        var result = await f.Service.ReadContentAsync(support, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNoGrant, result.Outcome);
    }

    [Fact]
    public async Task Grant_for_a_different_document_does_not_authorize_this_one()
    {
        var f = NewFixture();
        var support = AuthContext.ForGlobalSupport("support-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        f.UnscopedIndex.Seed(new Document("doc-2", TenantA, "contrato-confidencial.pdf"));
        await f.Grants.IssueAsync(new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1", // grant só para doc-1
            Reason = "Ticket #4821",
            IssuedAtUtc = DateTimeOffset.UtcNow,
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(1),
        });

        var result = await f.Service.ReadContentAsync(support, "doc-2", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNoGrant, result.Outcome);
    }

    [Fact]
    public async Task Grant_for_a_different_actor_does_not_authorize_this_one()
    {
        var f = NewFixture();
        var attackerImpersonatingSupport = AuthContext.ForGlobalSupport("support-2"); // não é o support-1 do grant
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        await f.Grants.IssueAsync(new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1",
            Reason = "Ticket #4821",
            IssuedAtUtc = DateTimeOffset.UtcNow,
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(1),
        });

        var result = await f.Service.ReadContentAsync(attackerImpersonatingSupport, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNoGrant, result.Outcome);
    }

    [Fact]
    public async Task Issuing_a_grant_without_a_reason_is_rejected()
    {
        var store = new InMemoryDocumentAccessGrantStore();
        var badGrant = new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1",
            Reason = "   ",
            IssuedAtUtc = DateTimeOffset.UtcNow,
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(1),
        };

        await Assert.ThrowsAsync<ArgumentException>(() => store.IssueAsync(badGrant));
    }

    [Fact]
    public async Task Issuing_a_grant_that_already_expires_at_issuance_is_rejected()
    {
        var store = new InMemoryDocumentAccessGrantStore();
        var now = DateTimeOffset.UtcNow;
        var badGrant = new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "support-1",
            DocumentId = "doc-1",
            Reason = "Motivo válido",
            IssuedAtUtc = now,
            ExpiresAtUtc = now, // não é posterior — janela vazia
        };

        await Assert.ThrowsAsync<ArgumentException>(() => store.IssueAsync(badGrant));
    }

    [Fact]
    public async Task Every_attempt_granted_or_denied_produces_an_audit_event()
    {
        var f = NewFixture();
        var support = AuthContext.ForGlobalSupport("support-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));

        await f.Service.ReadContentAsync(support, "doc-1", CorrelationId); // negado, sem grant

        var globalEvents = await f.GlobalAudit.ListAsync();
        var e = Assert.Single(globalEvents);
        Assert.Equal(nameof(DocumentContentAccessOutcome.DeniedNoGrant), e.Metadata.Values["decision"]);
        Assert.Equal("support-1", e.ActorId);
        Assert.Equal(AuditAction.DocumentContentAccessed, e.Action);
        Assert.Equal(AuditResource.Document, e.Resource);
        Assert.Equal("doc-1", e.ResourceId);
        Assert.Equal(CorrelationId, e.CorrelationId);
    }

    [Fact]
    public async Task Super_admin_granted_access_is_audited_with_the_granted_detail()
    {
        var f = NewFixture();
        var superAdmin = AuthContext.ForSuperAdmin("root-1");
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        await f.Grants.IssueAsync(new DocumentAccessGrant
        {
            Id = "grant-1",
            GrantedToActorId = "root-1",
            DocumentId = "doc-1",
            Reason = "Auditoria de compliance solicitada pelo tenant",
            IssuedAtUtc = DateTimeOffset.UtcNow,
            ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(1),
        });

        await f.Service.ReadContentAsync(superAdmin, "doc-1", CorrelationId);

        var globalEvents = await f.GlobalAudit.ListAsync();
        var e = Assert.Single(globalEvents);
        Assert.Equal(nameof(DocumentContentAccessOutcome.GrantedByExplicitGrant), e.Metadata.Values["decision"]);
        Assert.Equal(Role.SuperAdmin, e.ActorRole);
    }

    [Fact]
    public async Task Viewer_from_another_tenant_hits_the_ordinary_tenant_isolation_wall_not_the_grant_path()
    {
        // VIEWER usa o caminho comum (tenant-scoped), nunca o de grant (exclusivo de
        // SUPPORT/SUPER_ADMIN) — então um VIEWER de outro tenant é barrado pela MESMA garantia de
        // CARREFOUR-PRINT-TENANT-ISOLATION.md, não por falta de grant. DeniedNotFound aqui, não
        // DeniedNoGrant, é o sinal correto de que passou pelo caminho certo.
        var f = NewFixture();
        var viewerElsewhere = AuthContext.ForUser("tenant-b", "viewer-1", Role.Viewer);
        f.UnscopedIndex.Seed(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));
        await f.TenantDocuments.InsertAsync(new Document("doc-1", TenantA, "nota-fiscal-a.pdf"));

        var result = await f.Service.ReadContentAsync(viewerElsewhere, "doc-1", CorrelationId);

        Assert.Equal(DocumentContentAccessOutcome.DeniedNotFound, result.Outcome);
    }
}
