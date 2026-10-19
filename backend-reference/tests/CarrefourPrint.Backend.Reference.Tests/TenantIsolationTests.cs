using CarrefourPrint.Backend.Reference;

namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova, com testes reais, a garantia central de CARREFOUR-PRINT-TENANT-ISOLATION.md:
/// Tenant A não consegue acessar nenhum recurso comercial de Tenant B — device, printer, job,
/// document, license, audit — nem por consulta direta por id, nem por listagem, nem tentando
/// injetar um tenantId próprio numa requisição.
///
/// Cada teste negativo tem um teste positivo irmão (Tenant A acessando SEU PRÓPRIO recurso) —
/// sem isso, um repositório quebrado que sempre retornasse null passaria todos os testes
/// negativos por acidente. Provar isolamento exige provar as duas coisas: nega o que deveria
/// negar, permite o que deveria permitir.
/// </summary>
public class TenantIsolationTests
{
    private const string TenantA = "tenant-a";
    private const string TenantB = "tenant-b";

    private static readonly AuthContext AsTenantA = AuthContext.ForUser(TenantA, "user-a1", Role.TenantAdmin);
    private static readonly AuthContext AsTenantB = AuthContext.ForUser(TenantB, "user-b1", Role.TenantAdmin);

    // ---------- Device ----------

    [Fact]
    public async Task Tenant_A_cannot_access_device_B()
    {
        var repo = new InMemoryTenantScopedRepository<Device>();
        await repo.InsertAsync(new Device("device-b1", TenantB, "Caixa 3 - Loja B"));

        var result = await repo.GetByIdAsync(AsTenantA, "device-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_device()
    {
        var repo = new InMemoryTenantScopedRepository<Device>();
        await repo.InsertAsync(new Device("device-a1", TenantA, "Caixa 1 - Loja A"));

        var result = await repo.GetByIdAsync(AsTenantA, "device-a1");

        Assert.NotNull(result);
        Assert.Equal(TenantA, result!.TenantId);
    }

    // ---------- Printer ----------

    [Fact]
    public async Task Tenant_A_cannot_access_printer_B()
    {
        var repo = new InMemoryTenantScopedRepository<Printer>();
        await repo.InsertAsync(new Printer("printer-b1", TenantB, "Expedição B"));

        var result = await repo.GetByIdAsync(AsTenantA, "printer-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_printer()
    {
        var repo = new InMemoryTenantScopedRepository<Printer>();
        await repo.InsertAsync(new Printer("printer-a1", TenantA, "Expedição A"));

        var result = await repo.GetByIdAsync(AsTenantA, "printer-a1");

        Assert.NotNull(result);
    }

    // ---------- PrintJob ----------

    [Fact]
    public async Task Tenant_A_cannot_access_job_B()
    {
        var repo = new InMemoryTenantScopedRepository<PrintJob>();
        await repo.InsertAsync(new PrintJob("job-b1", TenantB, "etiqueta-confidencial-b"));

        var result = await repo.GetByIdAsync(AsTenantA, "job-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_job()
    {
        var repo = new InMemoryTenantScopedRepository<PrintJob>();
        await repo.InsertAsync(new PrintJob("job-a1", TenantA, "etiqueta-a"));

        var result = await repo.GetByIdAsync(AsTenantA, "job-a1");

        Assert.NotNull(result);
    }

    // ---------- Document ----------

    [Fact]
    public async Task Tenant_A_cannot_access_document_B()
    {
        var repo = new InMemoryTenantScopedRepository<Document>();
        await repo.InsertAsync(new Document("doc-b1", TenantB, "nota-fiscal-b.pdf"));

        var result = await repo.GetByIdAsync(AsTenantA, "doc-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_document()
    {
        var repo = new InMemoryTenantScopedRepository<Document>();
        await repo.InsertAsync(new Document("doc-a1", TenantA, "nota-fiscal-a.pdf"));

        var result = await repo.GetByIdAsync(AsTenantA, "doc-a1");

        Assert.NotNull(result);
    }

    // ---------- License ----------

    [Fact]
    public async Task Tenant_A_cannot_access_license_B()
    {
        var repo = new InMemoryTenantScopedRepository<License>();
        await repo.InsertAsync(new License("license-b1", TenantB, MaxDevices: 50));

        var result = await repo.GetByIdAsync(AsTenantA, "license-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_license()
    {
        var repo = new InMemoryTenantScopedRepository<License>();
        await repo.InsertAsync(new License("license-a1", TenantA, MaxDevices: 5));

        var result = await repo.GetByIdAsync(AsTenantA, "license-a1");

        Assert.NotNull(result);
    }

    // ---------- AuditEvent ----------

    private static AuditEvent NewAuditEvent(string id, string tenantId, AuditAction action) => new()
    {
        Id = id,
        TenantId = tenantId,
        ActorId = "actor-irrelevant",
        ActorKind = ActorKind.User,
        Action = action,
        Resource = AuditResource.Device,
        ResourceId = "device-irrelevant",
        Outcome = AuditOutcome.Success,
        CorrelationId = "corr-irrelevant",
        OccurredAtUtc = DateTimeOffset.UtcNow,
    };

    [Fact]
    public async Task Tenant_A_cannot_access_audit_event_B()
    {
        var repo = new InMemoryTenantScopedRepository<AuditEvent>();
        await repo.InsertAsync(NewAuditEvent("audit-b1", TenantB, AuditAction.DeviceRevoked));

        var result = await repo.GetByIdAsync(AsTenantA, "audit-b1");

        Assert.Null(result);
    }

    [Fact]
    public async Task Tenant_A_can_access_its_own_audit_event()
    {
        var repo = new InMemoryTenantScopedRepository<AuditEvent>();
        await repo.InsertAsync(NewAuditEvent("audit-a1", TenantA, AuditAction.DeviceActivated));

        var result = await repo.GetByIdAsync(AsTenantA, "audit-a1");

        Assert.NotNull(result);
    }

    // ---------- Listagem nunca vaza entre tenants (não só busca por id) ----------

    [Fact]
    public async Task Listing_devices_as_tenant_A_never_includes_tenant_B_devices()
    {
        var repo = new InMemoryTenantScopedRepository<Device>();
        await repo.InsertAsync(new Device("device-a1", TenantA, "Caixa 1 - Loja A"));
        await repo.InsertAsync(new Device("device-a2", TenantA, "Caixa 2 - Loja A"));
        await repo.InsertAsync(new Device("device-b1", TenantB, "Caixa 3 - Loja B"));

        var result = await repo.ListAsync(AsTenantA);

        Assert.Equal(2, result.Count);
        Assert.All(result, d => Assert.Equal(TenantA, d.TenantId));
        Assert.DoesNotContain(result, d => d.Id == "device-b1");
    }

    // ---------- IDs colidentes entre tenants não vazam (ids não são globalmente únicos por si só) ----------

    [Fact]
    public async Task Same_id_reused_by_two_tenants_never_leaks_the_other_tenants_record()
    {
        // Cenário realista: dois tenants, cada um com seu próprio gerador de id (ou um atacante
        // deliberadamente tentando adivinhar um id de outro tenant) — o MESMO valor de id existe
        // para os dois. Tenant A só pode enxergar a versão que pertence a ele.
        var repo = new InMemoryTenantScopedRepository<Printer>();
        await repo.InsertAsync(new Printer("printer-001", TenantA, "Impressora legítima de A"));
        await repo.InsertAsync(new Printer("printer-001", TenantB, "Impressora legítima de B"));

        var resultForA = await repo.GetByIdAsync(AsTenantA, "printer-001");
        var resultForB = await repo.GetByIdAsync(AsTenantB, "printer-001");

        Assert.Equal("Impressora legítima de A", resultForA!.Name);
        Assert.Equal("Impressora legítima de B", resultForB!.Name);
    }

    // ---------- Não confiar em tenantId enviado pelo cliente ----------

    [Fact]
    public async Task Untrusted_client_supplied_tenant_id_in_the_request_is_never_used()
    {
        // Simula exatamente o erro que a instrução do usuário pede para nunca cometer: um campo
        // de requisição carregando um tenantId "solicitado" pelo cliente. A camada de serviço
        // (DeviceService) nem lê esse campo — a prova é que o resultado é idêntico com ou sem ele,
        // porque a única fonte de tenant é o AuthContext já verificado, nunca o corpo da requisição.
        var repo = new InMemoryTenantScopedRepository<Device>();
        await repo.InsertAsync(new Device("device-b1", TenantB, "Caixa 3 - Loja B"));
        var service = new DeviceService(repo);

        var maliciousRequest = new GetDeviceRequest
        {
            DeviceId = "device-b1",
            UntrustedRequestedTenantId = TenantB, // um cliente comprometido/malicioso tentando se passar por B
        };

        // O ator autenticado continua sendo Tenant A (é isso que o token/AuthContext prova) —
        // nenhum campo do corpo da requisição pode sobrepor essa identidade.
        var result = await service.GetDeviceAsync(AsTenantA, maliciousRequest);

        Assert.Null(result);
    }

    [Fact]
    public async Task Service_layer_ignores_the_untrusted_field_even_for_the_actors_own_tenant()
    {
        // Reforça que o campo é IGNORADO, não "às vezes usado" — mesmo quando o valor malicioso
        // coincide com o tenant correto, o resultado tem que ser o mesmo que sem o campo,
        // provando que ele nunca entra na decisão de forma alguma.
        var repo = new InMemoryTenantScopedRepository<Device>();
        await repo.InsertAsync(new Device("device-a1", TenantA, "Caixa 1 - Loja A"));
        var service = new DeviceService(repo);

        var withField = await service.GetDeviceAsync(AsTenantA, new GetDeviceRequest { DeviceId = "device-a1", UntrustedRequestedTenantId = TenantA });
        var withoutField = await service.GetDeviceAsync(AsTenantA, new GetDeviceRequest { DeviceId = "device-a1", UntrustedRequestedTenantId = null });

        Assert.Equal(withoutField, withField);
        Assert.NotNull(withField);
    }
}
