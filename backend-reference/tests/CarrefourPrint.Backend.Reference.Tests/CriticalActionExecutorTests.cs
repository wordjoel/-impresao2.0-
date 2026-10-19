namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova a garantia central pedida nesta revisão: "ações administrativas críticas devem gerar
/// AuditEvent" e "SUPER_ADMIN também deve ser auditado" — não como afirmação em Markdown, mas como
/// comportamento que falha se alguém remover o `finally` de CriticalActionExecutor.cs.
/// </summary>
public class CriticalActionExecutorTests
{
    private static CriticalActionExecutor NewExecutor(
        ITenantScopedRepository<AuditEvent> tenantAudit,
        IGlobalAuditLog globalAudit) =>
        new(tenantAudit, globalAudit, TimeProvider.System);

    [Fact]
    public async Task Successful_tenant_scoped_action_is_audited()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var ctx = AuthContext.ForUser("tenant-a", "admin-1", Role.TenantAdmin);

        await executor.ExecuteAsync(ctx, AuditAction.DeviceRevoked, AuditResource.Device, "device-1", "corr-1", () => Task.FromResult(true));

        var events = await tenantAudit.ListAsync(ctx);
        var e = Assert.Single(events);
        Assert.Equal(AuditOutcome.Success, e.Outcome);
        Assert.Equal("admin-1", e.ActorId);
        Assert.Equal(Role.TenantAdmin, e.ActorRole);
        Assert.Equal(AuditAction.DeviceRevoked, e.Action);
        Assert.Equal(AuditResource.Device, e.Resource);
        Assert.Equal("device-1", e.ResourceId);
        Assert.Equal("corr-1", e.CorrelationId);
        Assert.Equal("tenant-a", e.TenantId);
        Assert.True(e.OccurredAtUtc > DateTimeOffset.UtcNow.AddMinutes(-1));
    }

    [Fact]
    public async Task Failed_tenant_scoped_action_is_still_audited_with_failure_outcome()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var ctx = AuthContext.ForUser("tenant-a", "admin-1", Role.TenantAdmin);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            executor.ExecuteAsync<bool>(ctx, AuditAction.DeviceRevoked, AuditResource.Device, "device-1", "corr-1", () => throw new InvalidOperationException("printer service down")));

        var events = await tenantAudit.ListAsync(ctx);
        var e = Assert.Single(events);
        Assert.Equal(AuditOutcome.Failure, e.Outcome);
        Assert.Equal("InvalidOperationException", e.Metadata.Values["exceptionType"]);
    }

    [Fact]
    public async Task Exception_message_never_reaches_audit_metadata_only_the_exception_type()
    {
        // A mensagem da exceção poderia carregar qualquer coisa que uma camada inferior não
        // sanitizou — inclusive, por acidente, algo que pareça um segredo. O metadado nunca deve
        // conter a mensagem, só o nome do tipo (sempre um identificador de código-fonte, nunca
        // dado em tempo de execução).
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var ctx = AuthContext.ForUser("tenant-a", "admin-1", Role.TenantAdmin);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            executor.ExecuteAsync<bool>(ctx, AuditAction.LicenseChanged, AuditResource.License, "license-1", "corr-1",
                () => throw new InvalidOperationException("token=abc123 secret leaked by a lower layer")));

        var e = Assert.Single(await tenantAudit.ListAsync(ctx));
        Assert.DoesNotContain(e.Metadata.Values, kv => kv.Value.Contains("abc123") || kv.Value.Contains("token="));
        Assert.Equal("InvalidOperationException", e.Metadata.Values["exceptionType"]);
    }

    [Fact]
    public async Task Super_admin_global_action_is_audited_in_the_global_log_not_the_tenant_log()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var superAdmin = AuthContext.ForSuperAdmin("root-1");

        await executor.ExecuteAsync(superAdmin, AuditAction.SecurityEvent, AuditResource.Tenant, "tenant-b", "corr-2", () => Task.FromResult(true));

        var globalEvents = await globalAudit.ListAsync();
        var e = Assert.Single(globalEvents);
        Assert.Equal("root-1", e.ActorId);
        Assert.Equal(Role.SuperAdmin, e.ActorRole);
        Assert.Equal(AuditOutcome.Success, e.Outcome);
        Assert.Equal(AuditResource.Tenant, e.Resource);
        Assert.Equal("tenant-b", e.ResourceId);
        Assert.Equal("corr-2", e.CorrelationId);

        // Nenhum tenant "ganhou" esse evento por engano — não existe tenant no qual ele apareça.
        var anyTenantCtx = AuthContext.ForUser("tenant-b", "irrelevant", Role.TenantAdmin);
        Assert.Empty(await tenantAudit.ListAsync(anyTenantCtx));
    }

    [Fact]
    public async Task Super_admin_failed_global_action_is_still_audited()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var superAdmin = AuthContext.ForSuperAdmin("root-1");

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            executor.ExecuteAsync<bool>(superAdmin, AuditAction.SecurityEvent, AuditResource.Tenant, "tenant-b", "corr-2", () => throw new InvalidOperationException("billing service down")));

        var globalEvents = await globalAudit.ListAsync();
        var e = Assert.Single(globalEvents);
        Assert.Equal(AuditOutcome.Failure, e.Outcome);
    }

    [Fact]
    public async Task Global_support_action_is_also_audited_in_the_global_log()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var globalSupport = AuthContext.ForGlobalSupport("support-1");

        await executor.ExecuteAsync(globalSupport, AuditAction.SecurityEvent, AuditResource.Device, "device-9", "corr-3", () => Task.FromResult(true));

        var globalEvents = await globalAudit.ListAsync();
        var e = Assert.Single(globalEvents);
        Assert.Equal(ActorKind.User, e.ActorKind);
        Assert.Equal(Role.Support, e.ActorRole);
    }

    [Fact]
    public async Task Describe_metadata_callback_records_business_decision_not_just_success()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = NewExecutor(tenantAudit, globalAudit);
        var ctx = AuthContext.ForUser("tenant-a", "admin-1", Role.TenantAdmin);

        await executor.ExecuteAsync(
            ctx,
            AuditAction.DocumentContentAccessed,
            AuditResource.Document,
            "doc-1",
            "corr-4",
            () => Task.FromResult("DENIED_NO_GRANT"),
            describeMetadata: result => new Dictionary<string, string> { ["decision"] = result });

        var e = Assert.Single(await tenantAudit.ListAsync(ctx));
        Assert.Equal("DENIED_NO_GRANT", e.Metadata.Values["decision"]);
        // A execução em si não lançou exceção — continua Outcome.Success (o SISTEMA funcionou),
        // é o metadado que carrega a decisão de negócio de negar.
        Assert.Equal(AuditOutcome.Success, e.Outcome);
    }

    [Fact]
    public void Metadata_rejects_any_key_that_looks_like_a_secret()
    {
        string[] forbiddenKeys = ["token", "licenseToken", "adminPin", "apiKeySecret", "userPassword", "credentialBlob"];

        foreach (var key in forbiddenKeys)
        {
            Assert.Throws<ArgumentException>(() => AuditMetadata.Of(new Dictionary<string, string> { [key] = "irrelevant" }));
        }
    }

    [Fact]
    public void Metadata_accepts_ordinary_operational_keys()
    {
        var metadata = AuditMetadata.Of(new Dictionary<string, string> { ["seatsRemaining"] = "3", ["reason"] = "DEVICE_REPLACED" });

        Assert.Equal("3", metadata.Values["seatsRemaining"]);
    }
}
