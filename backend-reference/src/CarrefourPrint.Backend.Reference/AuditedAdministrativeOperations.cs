namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Um método por ação auditável pedida nesta revisão — activation, revocation, license change,
/// printer configuration, job creation, job cancellation, retry administrativo, login
/// administrativo, security event. Cada método é uma casca fina sobre
/// <see cref="CriticalActionExecutor.ExecuteAsync{T}"/> que fixa o par (<see cref="AuditAction"/>,
/// <see cref="AuditResource"/>) correto em tempo de compilação — quem chama
/// <see cref="RecordDeviceActivationAsync{T}"/> não consegue, por engano, gravar o evento como
/// <see cref="AuditResource.License"/>, porque o método não aceita esse parâmetro.
///
/// A mutação de negócio em si (de fato ativar um dispositivo, mudar uma licença, etc.) é sempre o
/// <paramref name="operation"/> fornecido pelo chamador — esta classe não reimplementa a lógica de
/// domínio de `Device`/`License`/`Printer`/`PrintJob` (isso já está descrito em
/// `CARREFOUR-PRINT-DEVICE-IDENTITY.md`/`CARREFOUR-PRINT-SERVER-LICENSING.md`/
/// `CARREFOUR-PRINT-CROSS-PLATFORM.md` e não é o que foi pedido aqui); a responsabilidade desta
/// classe é garantir que a operação, seja lá qual for, produz o `AuditEvent` certo, sempre.
/// </summary>
public sealed class AuditedAdministrativeOperations(CriticalActionExecutor auditExecutor)
{
    /// <summary>Um dispositivo passa a `ACTIVE` numa licença — `CARREFOUR-PRINT-DEVICE-IDENTITY.md` §2.4.</summary>
    public Task<T> RecordDeviceActivationAsync<T>(
        AuthContext ctx, string deviceId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.DeviceActivated, AuditResource.Device, deviceId, correlationId, operation, describeMetadata, ct);

    /// <summary>`DeviceRevocation` — `CARREFOUR-PRINT-DEVICE-IDENTITY.md` §2.5. <paramref name="operation"/> deve liberar o seat associado como parte da mesma transação de negócio.</summary>
    public Task<T> RecordDeviceRevocationAsync<T>(
        AuthContext ctx, string deviceId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.DeviceRevoked, AuditResource.Device, deviceId, correlationId, operation, describeMetadata, ct);

    /// <summary>Mudança de estado/plano/seats de uma `License` — `CARREFOUR-PRINT-SERVER-LICENSING.md` §3.</summary>
    public Task<T> RecordLicenseChangeAsync<T>(
        AuthContext ctx, string licenseId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.LicenseChanged, AuditResource.License, licenseId, correlationId, operation, describeMetadata, ct);

    /// <summary>Cadastro/edição de IP, porta ou modelo de uma `Printer` — `CARREFOUR-PRINT-CROSS-PLATFORM.md` §5.</summary>
    public Task<T> RecordPrinterConfigurationAsync<T>(
        AuthContext ctx, string printerId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.PrinterConfigured, AuditResource.Printer, printerId, correlationId, operation, describeMetadata, ct);

    /// <summary>Criação de um `PrintJob` — `CARREFOUR-PRINT-CROSS-PLATFORM.md` §6. Ator normalmente `Device`/`ApiKey`, mas um `User` do Console também pode criar manualmente.</summary>
    public Task<T> RecordPrintJobCreationAsync<T>(
        AuthContext ctx, string jobId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.PrintJobCreated, AuditResource.PrintJob, jobId, correlationId, operation, describeMetadata, ct);

    /// <summary>Cancelamento de um `PrintJob` ainda não impresso.</summary>
    public Task<T> RecordPrintJobCancellationAsync<T>(
        AuthContext ctx, string jobId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.PrintJobCancelled, AuditResource.PrintJob, jobId, correlationId, operation, describeMetadata, ct);

    /// <summary>
    /// Um admin força o reenvio de um `PrintJob` que já falhou/expirou — diferente de um retry
    /// automático do worker de fila (`CARREFOUR-PRINT-CROSS-PLATFORM.md` §7), que é operação de
    /// sistema, não ação administrativa, e por isso não gera este evento (mesmo raciocínio já
    /// aplicado a heartbeat/rate-limit em `CARREFOUR-PRINT-API-KEYS.md` §10 — volume alto e
    /// automático não vira trilha administrativa).
    /// </summary>
    public Task<T> RecordAdministrativeRetryAsync<T>(
        AuthContext ctx, string jobId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.AdministrativeRetry, AuditResource.PrintJob, jobId, correlationId, operation, describeMetadata, ct);

    /// <summary>
    /// Um `User` autentica no Console administrativo. <paramref name="sessionId"/> é o id da
    /// sessão criada por essa autenticação (não o `ActorId`/`userId`, que já está em
    /// <paramref name="ctx"/>) — o "recurso" desta ação é a sessão nova, não o usuário em si.
    /// </summary>
    public Task<T> RecordAdministrativeLoginAsync<T>(
        AuthContext ctx, string sessionId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.AdministrativeLogin, AuditResource.Session, sessionId, correlationId, operation, describeMetadata, ct);

    /// <summary>
    /// Evento de segurança genérico — o único dos nove sem um tipo de recurso fixo por natureza
    /// (uma assinatura de desafio inválida é sobre um `Device`; uma tentativa de acesso cross-tenant
    /// é sobre o `Tenant` alvo; uma anomalia de sessão é sobre uma `Session`) — por isso
    /// <paramref name="resource"/> é explícito aqui, diferente dos outros oito métodos, onde o
    /// recurso é sempre o mesmo e por isso fixado no próprio nome do método.
    /// </summary>
    public Task<T> RecordSecurityEventAsync<T>(
        AuthContext ctx, AuditResource resource, string resourceId, string correlationId, Func<Task<T>> operation,
        Func<T, IReadOnlyDictionary<string, string>>? describeMetadata = null, CancellationToken ct = default) =>
        auditExecutor.ExecuteAsync(ctx, AuditAction.SecurityEvent, resource, resourceId, correlationId, operation, describeMetadata, ct);
}
