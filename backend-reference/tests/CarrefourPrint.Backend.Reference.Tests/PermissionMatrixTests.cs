namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova que PermissionMatrix.cs é de fato a tabela de AUTHORIZATION.md §4, célula a célula — não
/// uma aproximação. Casos escolhidos para cobrir: escrita negada por role somente-leitura, escrita
/// negada por role sem acesso ao recurso, leitura permitida quando a role tem só Read, e a garantia
/// de fail-closed para qualquer par (Role, Resource) não declarado explicitamente.
/// </summary>
public class PermissionMatrixTests
{
    [Fact]
    public void Viewer_can_read_printers_but_not_write()
    {
        Assert.True(PermissionMatrix.Allows(Role.Viewer, Resource.Printer, PermissionLevel.Read));
        Assert.False(PermissionMatrix.Allows(Role.Viewer, Resource.Printer, PermissionLevel.ReadWrite));
    }

    [Fact]
    public void Operator_can_manage_printers_and_devices()
    {
        Assert.True(PermissionMatrix.Allows(Role.Operator, Resource.Printer, PermissionLevel.ReadWrite));
        Assert.True(PermissionMatrix.Allows(Role.Operator, Resource.Device, PermissionLevel.ReadWrite));
    }

    [Fact]
    public void Operator_has_no_access_to_audit_log_or_tenant_config()
    {
        // AUTHORIZATION.md §4: linha Auditoria e Configurações do tenant marcam "—" para OPERATOR.
        Assert.False(PermissionMatrix.Allows(Role.Operator, Resource.AuditLog, PermissionLevel.Read));
        Assert.False(PermissionMatrix.Allows(Role.Operator, Resource.TenantConfig, PermissionLevel.Read));
    }

    [Fact]
    public void Support_is_read_only_everywhere_it_has_any_access()
    {
        foreach (var (key, level) in PermissionMatrix.AllEntries)
        {
            if (key.Role != Role.Support)
            {
                continue;
            }

            Assert.True(level is PermissionLevel.None or PermissionLevel.Read, $"SUPPORT não deveria ter ReadWrite em {key.Resource} (AUTHORIZATION.md §3: 'sem poder de billing/exclusão').");
        }
    }

    [Fact]
    public void Only_super_admin_and_tenant_admin_manage_users()
    {
        Assert.True(PermissionMatrix.Allows(Role.SuperAdmin, Resource.User, PermissionLevel.ReadWrite));
        Assert.True(PermissionMatrix.Allows(Role.TenantAdmin, Resource.User, PermissionLevel.ReadWrite));
        Assert.False(PermissionMatrix.Allows(Role.Support, Resource.User, PermissionLevel.ReadWrite));
        Assert.False(PermissionMatrix.Allows(Role.Operator, Resource.User, PermissionLevel.ReadWrite));
        Assert.False(PermissionMatrix.Allows(Role.Viewer, Resource.User, PermissionLevel.ReadWrite));
    }

    [Fact]
    public void Only_super_admin_manages_tenants_themselves()
    {
        foreach (var role in Enum.GetValues<Role>())
        {
            var expected = role == Role.SuperAdmin;
            Assert.Equal(expected, PermissionMatrix.Allows(role, Resource.Tenant, PermissionLevel.ReadWrite));
        }
    }

    [Fact]
    public void Undeclared_role_resource_pair_fails_closed()
    {
        // Nenhuma combinação (Role, Resource) real usada no sistema deveria estar ausente da
        // tabela — mas se um Resource novo for adicionado ao enum e alguém esquecer de declarar
        // uma célula, o comportamento tem que ser "nega", nunca "libera por omissão".
        Assert.False(PermissionMatrix.Allows(Role.Viewer, (Resource)9999, PermissionLevel.Read));
    }

    [Fact]
    public void Every_role_resource_combination_from_the_table_is_explicitly_declared()
    {
        var roles = Enum.GetValues<Role>();
        var resources = Enum.GetValues<Resource>();

        foreach (var role in roles)
        {
            foreach (var resource in resources)
            {
                Assert.True(
                    PermissionMatrix.AllEntries.ContainsKey((role, resource)),
                    $"Faltando declaração explícita para ({role}, {resource}) em PermissionMatrix — nunca deixar uma célula implícita.");
            }
        }
    }
}
