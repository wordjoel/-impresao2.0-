using System.Collections.Concurrent;

namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Estado local — espelha exatamente `br.com.atos.printbridge.domain.model.PrintJobStatus`
/// (Android) e `CarrefourPrint.Bridge.Domain.Models.JobStatus` (Windows),
/// `CARREFOUR-PRINT-CROSS-PLATFORM.md` §7. **Deliberadamente sem um valor `Printed`** — pedido
/// desta revisão: "não marcar PRINTED sem confirmação física verificável", e nenhum canal de
/// confirmação física existe hoje em nenhuma das duas impressoras alvo (`CARREFOUR-PRINT-TRAFFIC-MAP.md`
/// §2.2). A ausência do valor é a garantia — não uma convenção de nunca usá-lo; provado por
/// reflexão em <c>PrintQueueConcurrencyTests.JobStatus_never_has_a_printed_value</c>.
/// </summary>
public enum JobStatus
{
    Pending,
    Processing,
    Sent,
    Failed,
    Cancelled,
}

/// <summary>Estados exclusivos da Cloud Queue, anteriores a qualquer device ter tocado no job — `CARREFOUR-PRINT-CLOUD-QUEUE-ORCHESTRATION.md` §2.1.</summary>
public enum CloudQueueJobStatus
{
    Queued,
    Assigned,
    DeliveredToDevice,
}

public sealed record QueuedPrintJob
{
    public required string CloudJobId { get; init; }
    public required string TenantId { get; init; }
    public required string AssignedDeviceId { get; init; }
    public required CloudQueueJobStatus Status { get; init; }
}

/// <summary>
/// Dedup de "retry cloud" no sentido Sistema Externo → API: sem isto, um cliente HTTP que retry
/// uma `POST /print-jobs` (porque não recebeu a resposta da primeira tentativa a tempo, cenário de
/// rede comum, nunca hipotético) criaria um segundo `PrintJob` idêntico, que seria endereçado,
/// baixado e impresso fisicamente duas vezes — a duplicidade mais cara de todas, porque não
/// depende de nenhum bug de código, só de uma latência de rede normal.
/// </summary>
public interface IIdempotencyKeyStore
{
    /// <summary>
    /// Reserva atomicamente (tenantId, idempotencyKey) → <paramref name="candidate"/> SE ainda não
    /// reservado. Retorna o próprio <paramref name="candidate"/> quando esta chamada venceu a
    /// reserva, ou o job de uma chamada concorrente anterior quando não venceu — **o objeto
    /// inteiro, nunca só um id**: um design anterior deste método retornava apenas o `cloudJobId`
    /// do vencedor, exigindo uma segunda leitura (`_jobs[existingId]`) para obter o job — essa
    /// segunda leitura tinha uma janela de corrida real (o vencedor podia ainda não ter publicado
    /// o job em `_jobs` no instante em que o perdedor tentava lê-lo), confirmada por um teste de
    /// concorrência real desta revisão que falhou de forma intermitente com
    /// `KeyNotFoundException`. Retornar o objeto na MESMA operação atômica que decide quem venceu
    /// elimina a janela — não existe um segundo passo para correr.
    /// </summary>
    QueuedPrintJob ReserveOrGetExisting(string tenantId, string idempotencyKey, QueuedPrintJob candidate);
}

/// <summary>
/// `ConcurrentDictionary.GetOrAdd(key, value)` (a sobrecarga de VALOR, não a de fábrica/delegate)
/// é uma operação atômica única — não "checar depois adicionar" em dois passos que um agendador de
/// threads pudesse intercalar. É o "transaction/lock apropriado" pedido nesta revisão para este
/// caso: nenhum lock explícito é necessário porque a primitiva já garante exclusão mútua na escrita
/// — e, com a correção acima, garante também que o valor devolvido já é o dado completo, não uma
/// referência que ainda precisa ser resolvida numa segunda leitura não-atômica.
/// </summary>
public sealed class InMemoryIdempotencyKeyStore : IIdempotencyKeyStore
{
    private readonly ConcurrentDictionary<(string TenantId, string Key), QueuedPrintJob> _reservations = new();

    public QueuedPrintJob ReserveOrGetExisting(string tenantId, string idempotencyKey, QueuedPrintJob candidate) =>
        _reservations.GetOrAdd((tenantId, idempotencyKey), candidate);
}

/// <summary>
/// O lado Cloud Queue do handoff (`CARREFOUR-PRINT-CLOUD-QUEUE-ORCHESTRATION.md`). Duas garantias
/// de duplicidade cobertas aqui: (1) `CreateJob` nunca cria dois jobs para a mesma
/// `idempotencyKey` — cobre retry do Sistema Externo; (2) `TryAcknowledgeDelivery` nunca transiciona
/// `DeliveredToDevice` mais de uma vez para o mesmo job — cobre um device reenviando o mesmo `ack`
/// (retry device) ou duas chamadas de ack concorrentes por qualquer motivo (reconexão disparando
/// duas tentativas de sync sobrepostas).
/// </summary>
public sealed class CloudPrintJobQueue(IIdempotencyKeyStore idempotencyStore)
{
    private readonly ConcurrentDictionary<string, QueuedPrintJob> _jobs = new();

    public QueuedPrintJob CreateJob(string tenantId, string assignedDeviceId, string idempotencyKey)
    {
        var candidate = new QueuedPrintJob
        {
            CloudJobId = Guid.NewGuid().ToString(),
            TenantId = tenantId,
            AssignedDeviceId = assignedDeviceId,
            Status = CloudQueueJobStatus.Assigned,
        };

        var winner = idempotencyStore.ReserveOrGetExisting(tenantId, idempotencyKey, candidate);
        // Toda chamada concorrente (vencedora ou não) indexa o MESMO objeto vencedor pelo seu id —
        // TryAdd é idempotente aqui: a primeira chamada a chegar grava, as demais são no-ops
        // porque o valor para esse id já é exatamente este mesmo objeto.
        _jobs.TryAdd(winner.CloudJobId, winner);
        return winner;
    }

    public QueuedPrintJob? GetById(string cloudJobId) => _jobs.GetValueOrDefault(cloudJobId);

    /// <summary>
    /// Transição atômica Assigned → DeliveredToDevice via compare-and-swap
    /// (`ConcurrentDictionary.TryUpdate`, que só aplica a troca se o valor não mudou desde a
    /// leitura — se mudou, o laço relê e tenta de novo). Retorna <c>true</c> só na PRIMEIRA
    /// chamada bem-sucedida; qualquer chamada depois disso — reenvio de ack por timeout de rede na
    /// resposta anterior, ou uma corrida genuína entre duas chamadas concorrentes — retorna
    /// <c>false</c> sem nenhum efeito colateral, nunca um erro. É este mecanismo que torna o ack
    /// idempotente (`CARREFOUR-PRINT-CLOUD-QUEUE-ORCHESTRATION.md` §3, passo 3).
    /// </summary>
    public bool TryAcknowledgeDelivery(string cloudJobId)
    {
        while (true)
        {
            if (!_jobs.TryGetValue(cloudJobId, out var current))
            {
                return false;
            }

            if (current.Status == CloudQueueJobStatus.DeliveredToDevice)
            {
                return false;
            }

            var updated = current with { Status = CloudQueueJobStatus.DeliveredToDevice };
            if (_jobs.TryUpdate(cloudJobId, updated, current))
            {
                return true;
            }
            // TryUpdate falhou => outra thread mudou o valor entre o TryGetValue e agora; relê e tenta de novo.
        }
    }
}

public sealed record LocalPrintJob
{
    public required string Id { get; init; }
    public required string CloudJobId { get; init; }
    public required string DocumentName { get; init; }
    public required JobStatus Status { get; init; }
    public int Attempts { get; init; }
    public DateTimeOffset? ClaimedAtUtc { get; init; }
}

/// <summary>
/// O lado Local Queue do handoff — o motor que de fato executa (`CARREFOUR-PRINT-CLOUD-QUEUE-ORCHESTRATION.md`
/// §0). Três garantias de duplicidade cobertas: (1) <see cref="InsertFromCloudQueue"/> nunca insere
/// duas vezes o mesmo `cloudJobId` (cobre reoferta por retry cloud); (2)
/// <see cref="TryClaimForProcessing"/> nunca deixa dois "workers" processarem o mesmo job ao mesmo
/// tempo (cobre retry device, timeout seguido de nova tentativa, e reconexão disparando duas
/// tentativas de processar simultaneamente — um tick agendado e uma tentativa manual, por exemplo);
/// (3) <see cref="ReclaimStaleProcessingJobs"/> só devolve a `Pending` um job PROCESSING há mais
/// tempo que <c>staleThreshold</c> — nunca um job cuja tentativa ainda pode estar genuinamente em
/// andamento (cobre restart/crash sem reclamar uma tentativa viva por engano).
/// </summary>
public sealed class LocalPrintJobQueue
{
    private readonly ConcurrentDictionary<string, LocalPrintJob> _byLocalId = new();
    private readonly ConcurrentDictionary<string, string> _cloudJobIdIndex = new();

    /// <summary>Equivalente em memória de um índice único `UNIQUE(cloudJobId)` no banco real — a garantia não depende de "checar antes de inserir" em código, que teria uma janela de corrida (TOCTOU); depende da atomicidade de <c>GetOrAdd</c>.</summary>
    public (LocalPrintJob Job, bool Inserted) InsertFromCloudQueue(string cloudJobId, string documentName)
    {
        var candidateLocalId = Guid.NewGuid().ToString();
        var winnerLocalId = _cloudJobIdIndex.GetOrAdd(cloudJobId, candidateLocalId);

        if (winnerLocalId != candidateLocalId)
        {
            return (_byLocalId[winnerLocalId], false);
        }

        var job = new LocalPrintJob
        {
            Id = candidateLocalId,
            CloudJobId = cloudJobId,
            DocumentName = documentName,
            Status = JobStatus.Pending,
            Attempts = 0,
        };
        _byLocalId[candidateLocalId] = job;
        return (job, true);
    }

    public LocalPrintJob? GetById(string localId) => _byLocalId.GetValueOrDefault(localId);

    /// <summary>
    /// "Reivindica" um job `Pending` para processamento — equivalente em memória de
    /// <c>UPDATE PrintJob SET status='PROCESSING' WHERE id=? AND status='PENDING'</c> (0 linhas
    /// afetadas = outra chamada já reivindicou). Retorna <c>false</c> se o job não existe, já não
    /// está `Pending`, ou perdeu a corrida de CAS para outra chamada concorrente.
    /// </summary>
    public bool TryClaimForProcessing(string localId, TimeProvider clock)
    {
        while (true)
        {
            if (!_byLocalId.TryGetValue(localId, out var current) || current.Status != JobStatus.Pending)
            {
                return false;
            }

            var updated = current with { Status = JobStatus.Processing, ClaimedAtUtc = clock.GetUtcNow() };
            if (_byLocalId.TryUpdate(localId, updated, current))
            {
                return true;
            }
        }
    }

    /// <summary>Só válido a partir de `Processing` — o envio ao transporte terminou sem exceção. Nunca `Printed` (não existe o valor, ver <see cref="JobStatus"/>).</summary>
    public bool MarkSent(string localId) => TryTransition(localId, from: JobStatus.Processing, to: JobStatus.Sent, mutate: j => j);

    /// <summary>
    /// Falha do envio (timeout, conexão recusada, I/O) — nunca marca `Sent`, mesmo quando a causa
    /// é um timeout cujo resultado real na impressora é desconhecido (`CARREFOUR-PRINT-TRAFFIC-MAP.md`
    /// §2.2: o protocolo RAW/JetDirect não confirma nada). Volta a `Pending` (retry) se
    /// `attempts &lt; maxAttempts`, senão vai a `Failed` — mesma máquina de estado de
    /// `CARREFOUR-PRINT-CROSS-PLATFORM.md` §7.
    /// </summary>
    public bool MarkFailedOrRetry(string localId, int maxAttempts)
    {
        while (true)
        {
            if (!_byLocalId.TryGetValue(localId, out var current) || current.Status != JobStatus.Processing)
            {
                return false;
            }

            var attempts = current.Attempts + 1;
            var nextStatus = attempts < maxAttempts ? JobStatus.Pending : JobStatus.Failed;
            var updated = current with { Status = nextStatus, Attempts = attempts, ClaimedAtUtc = null };
            if (_byLocalId.TryUpdate(localId, updated, current))
            {
                return true;
            }
        }
    }

    private bool TryTransition(string localId, JobStatus from, JobStatus to, Func<LocalPrintJob, LocalPrintJob> mutate)
    {
        while (true)
        {
            if (!_byLocalId.TryGetValue(localId, out var current) || current.Status != from)
            {
                return false;
            }

            var updated = mutate(current) with { Status = to };
            if (_byLocalId.TryUpdate(localId, updated, current))
            {
                return true;
            }
        }
    }

    /// <summary>
    /// Recuperação de órfãos após restart/crash: um job `Processing` cujo `ClaimedAtUtc` é mais
    /// antigo que <paramref name="staleThreshold"/> volta a `Pending` para ser tentado de novo.
    /// Nunca reclama um job com claim recente — preservaria uma tentativa que ainda pode estar
    /// genuinamente em andamento (processo vivo, só lento), o que causaria dois processamentos
    /// simultâneos do mesmo job.
    /// </summary>
    public int ReclaimStaleProcessingJobs(TimeSpan staleThreshold, TimeProvider clock)
    {
        var reclaimed = 0;
        var now = clock.GetUtcNow();
        foreach (var (localId, job) in _byLocalId)
        {
            if (job.Status != JobStatus.Processing || job.ClaimedAtUtc is null)
            {
                continue;
            }

            if (now - job.ClaimedAtUtc.Value < staleThreshold)
            {
                continue;
            }

            var updated = job with { Status = JobStatus.Pending, ClaimedAtUtc = null };
            if (_byLocalId.TryUpdate(localId, updated, job))
            {
                reclaimed++;
            }
        }

        return reclaimed;
    }
}
