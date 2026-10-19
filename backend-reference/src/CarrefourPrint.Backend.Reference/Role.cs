namespace CarrefourPrint.Backend.Reference;

/// <summary>AUTHORIZATION.md §3 — os cinco perfis, agora como enum (era string livre antes desta revisão).</summary>
public enum Role
{
    SuperAdmin,
    TenantAdmin,
    Support,
    Operator,
    Viewer,
}
