namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Os recursos comerciais nomeados em AUTHORIZATION.md §4. `DocumentContent` é deliberadamente
/// separado de `DocumentMetadata` — a tabela original tratava "Documentos" como uma linha só, mas
/// o pedido desta revisão ("SUPPORT não deve possuir acesso indiscriminado a documentos") só faz
/// sentido para o CONTEÚDO do documento; metadados (nome, tamanho, tenant, timestamps) continuam
/// de leitura livre nos mesmos termos de antes — ver <see cref="DocumentContentAccessService"/>
/// para a restrição adicional que só se aplica a `DocumentContent`.
/// </summary>
public enum Resource
{
    Tenant,
    User,
    License,
    Device,
    Printer,
    DocumentMetadata,
    DocumentContent,
    PrintJob,
    AuditLog,
    TenantConfig,
}

/// <summary>
/// Simplificação deliberada das células "CRUD"/"Full"/"Read + diagnóstico"/"Read + solicitar
/// mudança" da tabela original em dois níveis. Nuances como "TENANT_ADMIN só pode SOLICITAR
/// mudança de licença, não editar direto" ou "SUPPORT tem Read + pacote de diagnóstico em Device"
/// são regras de fluxo de negócio específicas, não niveis de permissão genéricos — ficam para o
/// serviço de cada recurso decidir, não para esta matriz achatar em mais um enum.
/// </summary>
public enum PermissionLevel
{
    None,
    Read,
    ReadWrite,
}

/// <summary>
/// Implementação executável, e agora testada, de AUTHORIZATION.md §4 — "esta matriz é a fonte de
/// verdade — a implementação (middleware/decorators na API) referencia esta tabela, não o
/// contrário". Antes desta revisão, a tabela existia só como Markdown; nada garantia que uma
/// implementação futura a seguisse. Esta classe é literalmente a tabela, em código, com testes que
/// falham se alguém a alterar sem atualizar o documento (ou vice-versa).
///
/// Ortogonal a <see cref="ITenantScopedRepository{T}"/>: aquele resolve "de qual tenant", este
/// resolve "esta role pode fazer X neste tipo de recurso" — CARREFOUR-PRINT-TENANT-ISOLATION.md §6
/// já apontava essa dimensão como pendente de teste; é isso que este arquivo fecha.
/// </summary>
public static class PermissionMatrix
{
    private static readonly Dictionary<(Role Role, Resource Resource), PermissionLevel> Table = new()
    {
        [(Role.SuperAdmin, Resource.Tenant)] = PermissionLevel.ReadWrite,
        [(Role.TenantAdmin, Resource.Tenant)] = PermissionLevel.None,
        [(Role.Support, Resource.Tenant)] = PermissionLevel.None,
        [(Role.Operator, Resource.Tenant)] = PermissionLevel.None,
        [(Role.Viewer, Resource.Tenant)] = PermissionLevel.None,

        [(Role.SuperAdmin, Resource.User)] = PermissionLevel.ReadWrite,
        [(Role.TenantAdmin, Resource.User)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.User)] = PermissionLevel.Read,
        [(Role.Operator, Resource.User)] = PermissionLevel.Read,
        [(Role.Viewer, Resource.User)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.License)] = PermissionLevel.ReadWrite,
        [(Role.TenantAdmin, Resource.License)] = PermissionLevel.Read,
        [(Role.Support, Resource.License)] = PermissionLevel.Read,
        [(Role.Operator, Resource.License)] = PermissionLevel.Read,
        [(Role.Viewer, Resource.License)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.Device)] = PermissionLevel.ReadWrite,
        [(Role.TenantAdmin, Resource.Device)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.Device)] = PermissionLevel.Read,
        [(Role.Operator, Resource.Device)] = PermissionLevel.ReadWrite,
        [(Role.Viewer, Resource.Device)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.Printer)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.Printer)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.Printer)] = PermissionLevel.Read,
        [(Role.Operator, Resource.Printer)] = PermissionLevel.ReadWrite,
        [(Role.Viewer, Resource.Printer)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.DocumentMetadata)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.DocumentMetadata)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.DocumentMetadata)] = PermissionLevel.Read,
        [(Role.Operator, Resource.DocumentMetadata)] = PermissionLevel.ReadWrite,
        [(Role.Viewer, Resource.DocumentMetadata)] = PermissionLevel.Read,

        // DocumentContent: os NÍVEIS são os mesmos de DocumentMetadata (a tabela original não
        // distinguia) — mas para SuperAdmin/Support, ter PermissionLevel.Read aqui é necessário,
        // não suficiente: DocumentContentAccessService exige também um DocumentAccessGrant válido.
        // Para TenantAdmin/Operator/Viewer, dentro do próprio tenant, o nível abaixo já é
        // suficiente — é o dado deles.
        [(Role.SuperAdmin, Resource.DocumentContent)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.DocumentContent)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.DocumentContent)] = PermissionLevel.Read,
        [(Role.Operator, Resource.DocumentContent)] = PermissionLevel.ReadWrite,
        [(Role.Viewer, Resource.DocumentContent)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.PrintJob)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.PrintJob)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.PrintJob)] = PermissionLevel.Read,
        [(Role.Operator, Resource.PrintJob)] = PermissionLevel.ReadWrite,
        [(Role.Viewer, Resource.PrintJob)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.AuditLog)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.AuditLog)] = PermissionLevel.Read,
        [(Role.Support, Resource.AuditLog)] = PermissionLevel.Read,
        [(Role.Operator, Resource.AuditLog)] = PermissionLevel.None,
        [(Role.Viewer, Resource.AuditLog)] = PermissionLevel.Read,

        [(Role.SuperAdmin, Resource.TenantConfig)] = PermissionLevel.Read,
        [(Role.TenantAdmin, Resource.TenantConfig)] = PermissionLevel.ReadWrite,
        [(Role.Support, Resource.TenantConfig)] = PermissionLevel.Read,
        [(Role.Operator, Resource.TenantConfig)] = PermissionLevel.None,
        [(Role.Viewer, Resource.TenantConfig)] = PermissionLevel.Read,
    };

    /// <summary>
    /// <paramref name="requested"/> é o nível MÍNIMO exigido pela operação (ex.: um DELETE exige
    /// ReadWrite). Retorna false para qualquer combinação (Role, Resource) ausente da tabela — uma
    /// omissão nunca vira acesso liberado por padrão (fail closed), ela vira uma falha de teste
    /// (<see cref="AllRoleResourceCombinationsAreExplicit"/> nos testes cobre isso).
    /// </summary>
    public static bool Allows(Role role, Resource resource, PermissionLevel requested)
    {
        if (!Table.TryGetValue((role, resource), out var granted))
        {
            return false;
        }

        return requested switch
        {
            PermissionLevel.None => true,
            PermissionLevel.Read => granted is PermissionLevel.Read or PermissionLevel.ReadWrite,
            PermissionLevel.ReadWrite => granted is PermissionLevel.ReadWrite,
            _ => false,
        };
    }

    /// <summary>Exposto só para o teste que garante que todo par (Role, Resource) foi decidido explicitamente — nunca deixado de fora por esquecimento.</summary>
    public static IReadOnlyDictionary<(Role Role, Resource Resource), PermissionLevel> AllEntries => Table;
}
