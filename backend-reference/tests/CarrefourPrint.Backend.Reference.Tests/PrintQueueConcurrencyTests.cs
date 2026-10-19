namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Prova, com concorrência real (não simulada por comentário), que nenhuma das cinco fontes de
/// duplicidade pedidas nesta revisão — retry cloud, retry device, timeout, reconexão, restart —
/// consegue causar um efeito duplicado. Cada teste dispara N chamadas verdadeiramente concorrentes
/// (`Task.WhenAll` sobre `Task.Run`, então em threads de pool distintas, não apenas Tasks
/// intercaladas cooperativamente) contra a mesma primitiva de dedup/CAS e verifica que exatamente
/// UMA teve o efeito colateral esperado. Corretude aqui não depende de timing/sorte — as primitivas
/// (`ConcurrentDictionary.GetOrAdd`/`TryUpdate`) são atômicas por construção, então estes testes são
/// determinísticos independentemente de como o agendador de threads intercala as chamadas.
/// </summary>
public class PrintQueueConcurrencyTests
{
    private const int Concurrency = 64;

    // ---------- Retry cloud (Sistema Externo repetindo POST /print-jobs) ----------

    [Fact]
    public async Task Concurrent_creates_with_the_same_idempotency_key_produce_exactly_one_job()
    {
        var queue = new CloudPrintJobQueue(new InMemoryIdempotencyKeyStore());

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => queue.CreateJob("tenant-a", "device-1", "idem-key-1"))));

        var distinctIds = results.Select(r => r.CloudJobId).Distinct().ToList();
        Assert.Single(distinctIds);
    }

    [Fact]
    public async Task Different_idempotency_keys_are_never_conflated()
    {
        var queue = new CloudPrintJobQueue(new InMemoryIdempotencyKeyStore());

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(i => Task.Run(() => queue.CreateJob("tenant-a", "device-1", $"idem-key-{i % 4}"))));

        // 4 chaves distintas usadas -> exatamente 4 jobs distintos, nunca mais (colisão) nem menos (perda).
        Assert.Equal(4, results.Select(r => r.CloudJobId).Distinct().Count());
    }

    // ---------- Retry cloud / reconexão (duas confirmações de entrega para o mesmo job) ----------

    [Fact]
    public async Task Concurrent_delivery_acknowledgements_for_the_same_job_succeed_exactly_once()
    {
        var queue = new CloudPrintJobQueue(new InMemoryIdempotencyKeyStore());
        var job = queue.CreateJob("tenant-a", "device-1", "idem-key-1");

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => queue.TryAcknowledgeDelivery(job.CloudJobId))));

        Assert.Single(results, succeeded => succeeded);
        Assert.Equal(CloudQueueJobStatus.DeliveredToDevice, queue.GetById(job.CloudJobId)!.Status);
    }

    [Fact]
    public async Task Acknowledging_an_already_delivered_job_again_is_a_no_op_never_an_error()
    {
        var queue = new CloudPrintJobQueue(new InMemoryIdempotencyKeyStore());
        var job = queue.CreateJob("tenant-a", "device-1", "idem-key-1");
        Assert.True(queue.TryAcknowledgeDelivery(job.CloudJobId));

        var secondAttempt = queue.TryAcknowledgeDelivery(job.CloudJobId); // reenvio de ack por timeout na resposta da 1ª chamada

        Assert.False(secondAttempt);
        Assert.Equal(CloudQueueJobStatus.DeliveredToDevice, queue.GetById(job.CloudJobId)!.Status);
    }

    // ---------- Retry cloud (reoferta do mesmo job por perda de ack) ----------

    [Fact]
    public async Task Concurrent_local_inserts_of_the_same_cloud_job_id_produce_exactly_one_local_job()
    {
        var localQueue = new LocalPrintJobQueue();

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf"))));

        Assert.Single(results, r => r.Inserted);
        Assert.Single(results.Select(r => r.Job.Id).Distinct());
    }

    // ---------- Retry device / timeout / reconexão (duas tentativas de processar o mesmo job) ----------

    [Fact]
    public async Task Concurrent_claims_for_processing_the_same_job_succeed_exactly_once()
    {
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var clock = TimeProvider.System;

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => localQueue.TryClaimForProcessing(job.Id, clock))));

        Assert.Single(results, succeeded => succeeded);
        Assert.Equal(JobStatus.Processing, localQueue.GetById(job.Id)!.Status);
    }

    [Fact]
    public async Task Claiming_a_job_that_is_not_pending_never_succeeds_even_under_concurrency()
    {
        // Simula reconexão disparando um retry MANUAL exatamente enquanto o worker automático já
        // reivindicou o job — nenhuma das tentativas concorrentes pode "roubar" um job já em voo.
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var clock = TimeProvider.System;
        Assert.True(localQueue.TryClaimForProcessing(job.Id, clock)); // já Processing

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => localQueue.TryClaimForProcessing(job.Id, clock))));

        Assert.All(results, succeeded => Assert.False(succeeded));
    }

    // ---------- Timeout: nunca marca Sent quando o resultado é ambíguo ----------

    [Fact]
    public void A_job_that_fails_after_a_timeout_is_never_marked_sent()
    {
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var clock = TimeProvider.System;
        Assert.True(localQueue.TryClaimForProcessing(job.Id, clock));

        // TRANSPORT_TIMEOUT (CARREFOUR-PRINT-CROSS-PLATFORM.md §8.1) — resultado real na
        // impressora é desconhecido; mesmo assim, o único destino possível é Pending (retry) ou
        // Failed, nunca Sent.
        Assert.True(localQueue.MarkFailedOrRetry(job.Id, maxAttempts: 3));

        var afterTimeout = localQueue.GetById(job.Id)!;
        Assert.NotEqual(JobStatus.Sent, afterTimeout.Status);
        Assert.Equal(JobStatus.Pending, afterTimeout.Status); // attempts=1 < maxAttempts=3
    }

    [Fact]
    public void After_exhausting_retries_following_repeated_timeouts_the_job_is_failed_never_sent()
    {
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var clock = TimeProvider.System;

        for (var i = 0; i < 3; i++)
        {
            Assert.True(localQueue.TryClaimForProcessing(job.Id, clock));
            Assert.True(localQueue.MarkFailedOrRetry(job.Id, maxAttempts: 3));
        }

        Assert.Equal(JobStatus.Failed, localQueue.GetById(job.Id)!.Status);
    }

    [Fact]
    public void Sent_is_only_reachable_from_processing_never_directly_from_pending()
    {
        // Reforça a máquina de estado: um caminho de código que tentasse marcar Sent sem passar
        // por uma reivindicação (Processing) — ex.: um bug que pulasse a etapa de envio — falha
        // aqui, não silenciosamente.
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf"); // Pending

        var marked = localQueue.MarkSent(job.Id);

        Assert.False(marked);
        Assert.Equal(JobStatus.Pending, localQueue.GetById(job.Id)!.Status);
    }

    // ---------- Restart: recuperação de órfãos nunca reclama uma tentativa genuinamente viva ----------

    [Fact]
    public void Reclaim_never_touches_a_recently_claimed_job()
    {
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var clock = TimeProvider.System;
        Assert.True(localQueue.TryClaimForProcessing(job.Id, clock)); // ClaimedAtUtc = agora

        var reclaimedCount = localQueue.ReclaimStaleProcessingJobs(TimeSpan.FromMinutes(5), clock);

        Assert.Equal(0, reclaimedCount);
        Assert.Equal(JobStatus.Processing, localQueue.GetById(job.Id)!.Status);
    }

    [Fact]
    public void Reclaim_returns_a_stale_processing_job_to_pending_after_a_simulated_crash()
    {
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var fakeClock = new FakeTimeProvider(DateTimeOffset.UtcNow);
        Assert.True(localQueue.TryClaimForProcessing(job.Id, fakeClock)); // "crash" acontece logo em seguida, nunca reporta Sent/Failed

        fakeClock.Advance(TimeSpan.FromMinutes(10)); // restart acontece bem depois

        var reclaimedCount = localQueue.ReclaimStaleProcessingJobs(TimeSpan.FromMinutes(5), fakeClock);

        Assert.Equal(1, reclaimedCount);
        Assert.Equal(JobStatus.Pending, localQueue.GetById(job.Id)!.Status);
    }

    [Fact]
    public async Task After_a_reclaim_only_one_concurrent_retry_attempt_can_claim_the_job_again()
    {
        // Fecha o ciclo completo restart -> reclaim -> retry: mesmo depois de um crash simulado e
        // da recuperação de órfãos, a garantia de "só um processador por vez" (mesma primitiva do
        // teste de retry device) continua valendo para a NOVA tentativa.
        var localQueue = new LocalPrintJobQueue();
        var (job, _) = localQueue.InsertFromCloudQueue("cloud-job-1", "nota-fiscal.pdf");
        var fakeClock = new FakeTimeProvider(DateTimeOffset.UtcNow);
        Assert.True(localQueue.TryClaimForProcessing(job.Id, fakeClock));
        fakeClock.Advance(TimeSpan.FromMinutes(10));
        Assert.Equal(1, localQueue.ReclaimStaleProcessingJobs(TimeSpan.FromMinutes(5), fakeClock));

        var results = await Task.WhenAll(Enumerable.Range(0, Concurrency)
            .Select(_ => Task.Run(() => localQueue.TryClaimForProcessing(job.Id, fakeClock))));

        Assert.Single(results, succeeded => succeeded);
    }

    // ---------- Nunca PRINTED — estrutural, não convenção ----------

    [Fact]
    public void JobStatus_never_has_a_printed_value()
    {
        var names = Enum.GetNames<JobStatus>();

        Assert.DoesNotContain(names, name => name.Equals("Printed", StringComparison.OrdinalIgnoreCase));
        Assert.Equal(new[] { "Pending", "Processing", "Sent", "Failed", "Cancelled" }, names);
    }

    /// <summary>Relógio controlável manualmente — evita `Thread.Sleep` real em teste de "tempo decorrido" para reclamação de órfãos.</summary>
    private sealed class FakeTimeProvider(DateTimeOffset start) : TimeProvider
    {
        private DateTimeOffset _now = start;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }
}
