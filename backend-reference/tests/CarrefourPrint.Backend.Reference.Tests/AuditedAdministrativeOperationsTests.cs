namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova, uma a uma, que as nove operações pedidas nesta revisão — activation, revocation, license
/// change, printer configuration, job creation, job cancellation, retry administrativo, login
/// administrativo, security event — geram um `AuditEvent` com os nove campos exigidos: tenant,
/// user/device, action, resource, resourceId, timestamp, result, correlationId, metadata mínima.
/// </summary>
public class AuditedAdministrativeOperationsTests
{
    private const string TenantA = "tenant-a";
    private const string CorrelationId = "corr-op-1";

    private sealed record Fixture(AuditedAdministrativeOperations Ops, ITenantScopedRepository<AuditEvent> TenantAudit, IGlobalAuditLog GlobalAudit);

    private static Fixture NewFixture()
    {
        var tenantAudit = new InMemoryTenantScopedRepository<AuditEvent>();
        var globalAudit = new InMemoryGlobalAuditLog();
        var executor = new CriticalActionExecutor(tenantAudit, globalAudit, TimeProvider.System);
        return new Fixture(new AuditedAdministrativeOperations(executor), tenantAudit, globalAudit);
    }

    private static void AssertCoreFields(AuditEvent e, string tenantId, string actorId, ActorKind actorKind, AuditAction action, AuditResource resource, string resourceId)
    {
        Assert.Equal(tenantId, e.TenantId);
        Assert.Equal(actorId, e.ActorId);
        Assert.Equal(actorKind, e.ActorKind);
        Assert.Equal(action, e.Action);
        Assert.Equal(resource, e.Resource);
        Assert.Equal(resourceId, e.ResourceId);
        Assert.Equal(AuditOutcome.Success, e.Outcome);
        Assert.Equal(CorrelationId, e.CorrelationId);
        Assert.True(e.OccurredAtUtc > DateTimeOffset.UtcNow.AddMinutes(-1) && e.OccurredAtUtc <= DateTimeOffset.UtcNow);
    }

    [Fact]
    public async Task Activation_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);

        await f.Ops.RecordDeviceActivationAsync(ctx, "device-1", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "admin-1", ActorKind.User, AuditAction.DeviceActivated, AuditResource.Device, "device-1");
    }

    [Fact]
    public async Task Revocation_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);

        await f.Ops.RecordDeviceRevocationAsync(ctx, "device-1", CorrelationId, () => Task.FromResult(true),
            describeMetadata: _ => new Dictionary<string, string> { ["reason"] = "DEVICE_REPLACED" });

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "admin-1", ActorKind.User, AuditAction.DeviceRevoked, AuditResource.Device, "device-1");
        Assert.Equal("DEVICE_REPLACED", e.Metadata.Values["reason"]);
    }

    [Fact]
    public async Task License_change_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);

        await f.Ops.RecordLicenseChangeAsync(ctx, "license-1", CorrelationId, () => Task.FromResult(true),
            describeMetadata: _ => new Dictionary<string, string> { ["newMaxDevices"] = "10" });

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "admin-1", ActorKind.User, AuditAction.LicenseChanged, AuditResource.License, "license-1");
        Assert.Equal("10", e.Metadata.Values["newMaxDevices"]);
    }

    [Fact]
    public async Task Printer_configuration_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "operator-1", Role.Operator);

        await f.Ops.RecordPrinterConfigurationAsync(ctx, "printer-1", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "operator-1", ActorKind.User, AuditAction.PrinterConfigured, AuditResource.Printer, "printer-1");
    }

    [Fact]
    public async Task Job_creation_by_a_device_is_audited()
    {
        // Ator tipicamente um Device (o Bridge), não um User — "user/device" no pedido original.
        var f = NewFixture();
        var ctx = AuthContext.ForDevice(TenantA, "device-1");

        await f.Ops.RecordPrintJobCreationAsync(ctx, "job-1", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "device-1", ActorKind.Device, AuditAction.PrintJobCreated, AuditResource.PrintJob, "job-1");
        Assert.Null(e.ActorRole); // Device nunca tem role — AUTHORIZATION.md §6
    }

    [Fact]
    public async Task Job_cancellation_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "operator-1", Role.Operator);

        await f.Ops.RecordPrintJobCancellationAsync(ctx, "job-1", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "operator-1", ActorKind.User, AuditAction.PrintJobCancelled, AuditResource.PrintJob, "job-1");
    }

    [Fact]
    public async Task Administrative_retry_is_audited_distinctly_from_automatic_worker_retry()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "support-1", Role.Support);

        await f.Ops.RecordAdministrativeRetryAsync(ctx, "job-1", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "support-1", ActorKind.User, AuditAction.AdministrativeRetry, AuditResource.PrintJob, "job-1");
    }

    [Fact]
    public async Task Administrative_login_is_audited()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);

        await f.Ops.RecordAdministrativeLoginAsync(ctx, "session-abc", CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "admin-1", ActorKind.User, AuditAction.AdministrativeLogin, AuditResource.Session, "session-abc");
    }

    [Fact]
    public async Task Failed_administrative_login_is_still_audited_with_failure_outcome()
    {
        // Uma tentativa de login MAL-SUCEDIDA é exatamente o caso onde auditoria importa mais —
        // detectar força bruta/credencial vazada depende de ver as falhas, não só os sucessos.
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "admin-1", Role.TenantAdmin);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            f.Ops.RecordAdministrativeLoginAsync<bool>(ctx, "session-abc", CorrelationId, () => throw new InvalidOperationException("credenciais inválidas")));

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        Assert.Equal(AuditOutcome.Failure, e.Outcome);
        Assert.Equal(AuditAction.AdministrativeLogin, e.Action);
    }

    [Fact]
    public async Task Security_event_can_target_any_resource_type()
    {
        var f = NewFixture();
        var ctx = AuthContext.ForUser(TenantA, "support-1", Role.Support);

        await f.Ops.RecordSecurityEventAsync(ctx, AuditResource.Device, "device-9", CorrelationId, () => Task.FromResult(true),
            describeMetadata: _ => new Dictionary<string, string> { ["signal"] = "CHALLENGE_SIGNATURE_INVALID" });

        var e = Assert.Single(await f.TenantAudit.ListAsync(ctx));
        AssertCoreFields(e, TenantA, "support-1", ActorKind.User, AuditAction.SecurityEvent, AuditResource.Device, "device-9");
        Assert.Equal("CHALLENGE_SIGNATURE_INVALID", e.Metadata.Values["signal"]);
    }

    [Fact]
    public async Task Security_event_from_a_global_actor_is_audited_in_the_global_log()
    {
        var f = NewFixture();
        var superAdmin = AuthContext.ForSuperAdmin("root-1");

        await f.Ops.RecordSecurityEventAsync(superAdmin, AuditResource.Tenant, TenantA, CorrelationId, () => Task.FromResult(true));

        var e = Assert.Single(await f.GlobalAudit.ListAsync());
        Assert.Equal(AuditAction.SecurityEvent, e.Action);
        Assert.Equal(AuditResource.Tenant, e.Resource);
        Assert.Equal(TenantA, e.ResourceId);
        Assert.Equal(Role.SuperAdmin, e.ActorRole);
    }
}

/// <summary>
/// Prova estrutural (não por convenção) de que um `AuditEvent` gravado não pode ser alterado nem
/// removido por nenhum código deste sistema — o pedido era "AuditEvent imutável", verificado aqui
/// por reflexão sobre a forma do tipo e do contrato de armazenamento, não só por leitura de código.
/// </summary>
public class AuditImmutabilityTests
{
    [Fact]
    public void AuditEvent_properties_are_init_only_never_mutable_setters()
    {
        foreach (var property in typeof(AuditEvent).GetProperties())
        {
            var setMethod = property.SetMethod;
            Assert.NotNull(setMethod);
            var isInitOnly = setMethod!.ReturnParameter
                .GetRequiredCustomModifiers()
                .Contains(typeof(System.Runtime.CompilerServices.IsExternalInit));
            Assert.True(isInitOnly, $"AuditEvent.{property.Name} tem um setter mutável — deveria ser `init`-only.");
        }
    }

    [Fact]
    public void GlobalAuditEvent_properties_are_init_only_never_mutable_setters()
    {
        foreach (var property in typeof(GlobalAuditEvent).GetProperties())
        {
            var setMethod = property.SetMethod;
            Assert.NotNull(setMethod);
            var isInitOnly = setMethod!.ReturnParameter
                .GetRequiredCustomModifiers()
                .Contains(typeof(System.Runtime.CompilerServices.IsExternalInit));
            Assert.True(isInitOnly, $"GlobalAuditEvent.{property.Name} tem um setter mutável — deveria ser `init`-only.");
        }
    }

    [Fact]
    public void Tenant_scoped_repository_contract_exposes_no_update_or_delete_method_for_AuditEvent()
    {
        var methodNames = typeof(ITenantScopedRepository<AuditEvent>).GetMethods().Select(m => m.Name).ToHashSet();

        Assert.Equal(new HashSet<string> { nameof(ITenantScopedRepository<AuditEvent>.GetByIdAsync), nameof(ITenantScopedRepository<AuditEvent>.ListAsync), nameof(ITenantScopedRepository<AuditEvent>.InsertAsync) }, methodNames);
    }

    [Fact]
    public void Global_audit_log_contract_exposes_no_update_or_delete_method()
    {
        var methodNames = typeof(IGlobalAuditLog).GetMethods().Select(m => m.Name).ToHashSet();

        Assert.Equal(new HashSet<string> { nameof(IGlobalAuditLog.RecordAsync), nameof(IGlobalAuditLog.ListAsync) }, methodNames);
    }

    [Fact]
    public void With_expression_never_mutates_the_original_event_only_creates_a_new_one()
    {
        var original = new AuditEvent
        {
            Id = "audit-1",
            TenantId = "tenant-a",
            ActorId = "admin-1",
            ActorKind = ActorKind.User,
            Action = AuditAction.LicenseChanged,
            Resource = AuditResource.License,
            ResourceId = "license-1",
            Outcome = AuditOutcome.Success,
            CorrelationId = "corr-1",
            OccurredAtUtc = DateTimeOffset.UtcNow,
        };

        var copy = original with { Outcome = AuditOutcome.Failure };

        Assert.Equal(AuditOutcome.Success, original.Outcome); // o original nunca muda
        Assert.Equal(AuditOutcome.Failure, copy.Outcome);
        Assert.NotSame(original, copy);
    }
}
