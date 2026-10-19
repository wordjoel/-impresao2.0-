namespace CarrefourPrint.Backend.Reference;

/// <summary>Contraparte de <see cref="ITenantScopedRepository{T}"/> para atores sem tenant — ver <see cref="GlobalAuditEvent"/>.</summary>
public interface IGlobalAuditLog
{
    Task RecordAsync(GlobalAuditEvent auditEvent, CancellationToken ct = default);

    Task<IReadOnlyList<GlobalAuditEvent>> ListAsync(CancellationToken ct = default);
}

/// <summary>
/// Implementação de referência em memória. Em produção, isto seria uma tabela/coleção separada da
/// auditoria por tenant — nunca a mesma tabela com uma linha "sem tenant" misturada, pelo mesmo
/// motivo estrutural documentado em <see cref="GlobalAuditEvent"/>.
/// </summary>
public sealed class InMemoryGlobalAuditLog : IGlobalAuditLog
{
    private readonly List<GlobalAuditEvent> _events = [];

    public Task RecordAsync(GlobalAuditEvent auditEvent, CancellationToken ct = default)
    {
        _events.Add(auditEvent);
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<GlobalAuditEvent>> ListAsync(CancellationToken ct = default)
    {
        IReadOnlyList<GlobalAuditEvent> snapshot = _events.ToList();
        return Task.FromResult(snapshot);
    }
}
