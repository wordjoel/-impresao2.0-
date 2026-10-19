namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Todo recurso comercial do backend implementa isto — CARREFOUR-PRINT-TENANT-ISOLATION.md §0
/// ("toda consulta de recurso comercial precisa considerar tenant"). O repositório genérico
/// (TenantScopedRepository&lt;T&gt;) depende só desta interface, nunca de conhecimento específico
/// de cada tipo de entidade, para que a regra de isolamento seja garantida uma vez, não reescrita
/// em cada repositório concreto.
/// </summary>
public interface ITenantOwned
{
    string Id { get; }
    string TenantId { get; }
}

/// <summary>CARREFOUR-PRINT-DEVICE-IDENTITY.md §2.1 — campos mínimos para este teste de referência.</summary>
public sealed record Device(string Id, string TenantId, string Label) : ITenantOwned;

/// <summary>CARREFOUR-PRINT-CROSS-PLATFORM.md §5 — versão backend (com TenantId, ausente no modelo do cliente, que nunca precisa dele).</summary>
public sealed record Printer(string Id, string TenantId, string Name) : ITenantOwned;

/// <summary>CARREFOUR-PRINT-CROSS-PLATFORM.md §6 — idem.</summary>
public sealed record PrintJob(string Id, string TenantId, string DocumentName) : ITenantOwned;

/// <summary>ARCHITECTURE-V2.md — Document Service, ainda não construído; modelado aqui só o suficiente para o teste de isolamento.</summary>
public sealed record Document(string Id, string TenantId, string FileName) : ITenantOwned;

/// <summary>CARREFOUR-PRINT-SERVER-LICENSING.md §3.</summary>
public sealed record License(string Id, string TenantId, int MaxDevices) : ITenantOwned;

/// <summary>Resultado de uma ação auditada — nunca omitido, mesmo em falha (ver <see cref="CriticalActionExecutor"/>).</summary>
public enum AuditOutcome
{
    Success,
    Failure,
}

// AuditEvent, GlobalAuditEvent, AuditAction, AuditResource e AuditMetadata vivem em Auditing.cs —
// grupo grande o bastante (e com invariante própria de segurança, AuditMetadata) para merecer
// arquivo dedicado em vez de continuar crescendo dentro deste.
