namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Garante estruturalmente — não por convenção de "lembrar de chamar o audit log" — que toda ação
/// administrativa crítica gera um <see cref="AuditEvent"/>/<see cref="GlobalAuditEvent"/>, para
/// QUALQUER ator, `SUPER_ADMIN` incluído. O `finally` é o mecanismo: o evento é gravado tanto se
/// <paramref name="operation"/> tiver sucesso quanto se lançar uma exceção — nunca só no caminho feliz.
///
/// Roteamento automático entre as duas trilhas de auditoria por <see cref="AuthContext.TenantId"/>:
/// nulo (SUPER_ADMIN/SUPPORT globais) vai para <see cref="IGlobalAuditLog"/>; preenchido vai para
/// <see cref="ITenantScopedRepository{AuditEvent}"/>. O chamador nunca escolhe manualmente — não
/// existe forma de um SUPER_ADMIN "esquecer" de ser auditado ou de a ação cair no log errado.
/// </summary>
public sealed class CriticalActionExecutor(
    ITenantScopedRepository<AuditEvent> tenantAuditLog,
    IGlobalAuditLog globalAuditLog,
    TimeProvider clock)
{
    /// <summary>
    /// <paramref name="correlationId"/> é sempre fornecido pelo chamador (ex.: o id da requisição
    /// HTTP que originou a ação) — nunca gerado aqui, porque um id gerado silenciosamente por quem
    /// grava o evento não correlacionaria nada de verdade fora deste processo.
    ///
    /// <paramref name="describeMetadata"/> é opcional e existe para ações com uma decisão de
    /// negócio própria além de "não lançou exceção" — ex.: uma tentativa de leitura NEGADA por
    /// falta de <see cref="DocumentAccessGrant"/> não lança exceção (é um resultado de negócio
    /// válido, não um erro de sistema), mas precisa aparecer no metadado como
    /// <c>{"decision": "DENIED_NO_GRANT"}</c>, não como um metadado vazio que esconderia a
    /// negativa. O dicionário retornado passa por <see cref="AuditMetadata.Of"/> — se alguma chave
    /// parecer carregar um segredo, a chamada inteira lança, nunca grava um evento incompleto.
    /// </summary>
    public async Task<T> ExecuteAsync<T>(
        AuthContext ctx,
        AuditAction action,
        AuditResource resource,
        string resourceId,
        string correlationId,
        Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null,
        CancellationToken ct = default)
    {
        var outcome = AuditOutcome.Failure;
        var metadata = AuditMetadata.Empty;
        try
        {
            var result = await operation();
            outcome = AuditOutcome.Success;
            metadata = describeMetadata is null ? AuditMetadata.Empty : AuditMetadata.Of(describeMetadata(result));
            return result;
        }
        catch (Exception ex)
        {
            // Nunca ex.Message aqui: uma mensagem de exceção pode conter qualquer coisa que uma
            // camada inferior não sanitizou (ex.: um valor de configuração, um trecho de payload)
            // — só o NOME do tipo é seguro por construção, porque é sempre um identificador de
            // código-fonte, nunca dado em tempo de execução.
            metadata = AuditMetadata.Of(new Dictionary<string, string> { ["exceptionType"] = ex.GetType().Name });
            throw;
        }
        finally
        {
            await RecordAsync(ctx, action, resource, resourceId, outcome, correlationId, metadata, ct);
        }
    }

    private async Task RecordAsync(
        AuthContext ctx,
        AuditAction action,
        AuditResource resource,
        string resourceId,
        AuditOutcome outcome,
        string correlationId,
        AuditMetadata metadata,
        CancellationToken ct)
    {
        var occurredAt = clock.GetUtcNow();

        if (ctx.TenantId is null)
        {
            await globalAuditLog.RecordAsync(
                new GlobalAuditEvent
                {
                    Id = Guid.NewGuid().ToString(),
                    ActorId = ctx.ActorId,
                    ActorKind = ctx.Kind,
                    ActorRole = ctx.Role,
                    Action = action,
                    Resource = resource,
                    ResourceId = resourceId,
                    Outcome = outcome,
                    CorrelationId = correlationId,
                    OccurredAtUtc = occurredAt,
                    Metadata = metadata,
                },
                ct);
            return;
        }

        await tenantAuditLog.InsertAsync(
            new AuditEvent
            {
                Id = Guid.NewGuid().ToString(),
                TenantId = ctx.TenantId,
                ActorId = ctx.ActorId,
                ActorKind = ctx.Kind,
                ActorRole = ctx.Role,
                Action = action,
                Resource = resource,
                ResourceId = resourceId,
                Outcome = outcome,
                CorrelationId = correlationId,
                OccurredAtUtc = occurredAt,
                Metadata = metadata,
            },
            ct);
    }
}
