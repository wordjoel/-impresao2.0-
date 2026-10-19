package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.EmptyPayloadException
import br.com.atos.printbridge.domain.error.InvalidCopiesException
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PayloadTooLargeException
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.queue.JobOriginProvider
import br.com.atos.printbridge.domain.queue.QueueDispatcher
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.validation.ZplValidator
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protege contra envio duplicado por cliques repetidos: a mesma combinação
 * (impressora + conteúdo + nome de documento) enviada duas vezes dentro da janela de debounce
 * resulta em um único job. Guarda em memória apenas — reinicia a cada processo, o que é
 * aceitável pois o risco real é o duplo-toque do operador, não duplicidade entre sessões.
 */
@Singleton
class DuplicateSubmissionGuard @Inject constructor() {
    private data class Entry(val timestampMillis: Long, val job: PrintJob)

    private val lastSubmission = ConcurrentHashMap<String, Entry>()
    private val windowMillis = 2_000L

    /**
     * Se a mesma chave foi submetida há menos de [windowMillis], devolve o job já criado
     * naquela submissão em vez de deixar um novo ser inserido — é isso que de fato bloqueia
     * o duplo-toque (só logar um aviso não bastava: o job duplicado ainda seria inserido).
     */
    fun previousIfDuplicate(key: String): PrintJob? {
        val now = System.currentTimeMillis()
        val last = lastSubmission[key]
        return if (last != null && (now - last.timestampMillis) < windowMillis) last.job else null
    }

    fun remember(key: String, job: PrintJob) {
        lastSubmission[key] = Entry(System.currentTimeMillis(), job)
    }

    private data class BatchEntry(val timestampMillis: Long, val jobs: List<PrintJob>)

    private val lastBatch = ConcurrentHashMap<String, BatchEntry>()

    /** Mesmo papel de [previousIfDuplicate] para um pedido de quantidade (N jobs de uma vez). */
    fun previousBatchIfDuplicate(key: String): List<PrintJob>? {
        val last = lastBatch[key] ?: return null
        return if ((System.currentTimeMillis() - last.timestampMillis) < windowMillis) last.jobs else null
    }

    fun rememberBatch(key: String, jobs: List<PrintJob>) {
        lastBatch[key] = BatchEntry(System.currentTimeMillis(), jobs)
    }
}

/**
 * Portão de licenciamento para CRIAÇÃO de trabalho novo — CARREFOUR-PRINT-SERVER-LICENSING.md §10.
 * Deliberadamente NÃO usado pelo motor de processamento da fila (`ProcessPrintQueueUseCase`/worker),
 * por `RetryPrintJobUseCase`, nem por `RecoverOrphanedJobsUseCase`: um job já persistido antes de a
 * licença entrar em RESTRICTED continua sendo propriedade da fila local até terminar — "não
 * destruir jobs já persistidos" significa também não deixar de processá-los, não só não apagá-los.
 * Só `EnqueuePrintJobUseCase`/`ReprintJobUseCase` (que criam uma LINHA NOVA de compromisso) são
 * gateados — a licença decide se um NOVO trabalho pode começar, nunca se um já aceito pode terminar.
 */
class EnqueuePrintJobUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
    private val printerRepository: PrinterRepository,
    private val duplicateGuard: DuplicateSubmissionGuard,
    private val queueDispatcher: QueueDispatcher,
    private val evaluateLicenseStatus: EvaluateLicenseStatusUseCase,
    private val settingsRepository: SettingsRepository,
    private val jobOrigin: JobOriginProvider = JobOriginProvider.None,
) {
    /** [maxAttempts] nulo = usa `AppSettings.maxAttempts` (antes o ajuste da tela nunca era aplicado). */
    suspend operator fun invoke(
        printerId: String?,
        type: PrintPayloadType,
        payload: String,
        documentName: String,
        copies: Int = 1,
        externalDocumentId: String? = null,
        maxAttempts: Int? = null,
        sourceApp: String? = null,
    ): PrintJob {
        val licenseStatus = evaluateLicenseStatus()
        if (licenseStatus != EffectiveLicenseStatus.OPERATING) {
            throw LicenseRestrictedException(licenseStatus.name)
        }

        if (payload.isBlank()) throw EmptyPayloadException()
        val payloadBytes = payload.toByteArray(type.wireCharset).size
        val maxBytes = PrintJob.maxPayloadBytesFor(type)
        if (payloadBytes > maxBytes) {
            throw PayloadTooLargeException(payloadBytes, maxBytes)
        }
        if (copies > PrintJob.MAX_COPIES) {
            throw InvalidCopiesException(copies, PrintJob.MAX_COPIES)
        }

        val targetPrinter = if (printerId != null) {
            printerRepository.getById(printerId)
        } else {
            printerRepository.getDefault()
        } ?: throw NoDefaultPrinterException()

        val debounceKey = "${targetPrinter.id}:$documentName:${payload.hashCode()}"
        duplicateGuard.previousIfDuplicate(debounceKey)?.let { previous ->
            Logger.event(LogLevel.WARN, "job_submission_debounced", jobId = previous.id, printerId = targetPrinter.id, extra = mapOf("documentName" to documentName))
            return previous
        }

        val now = System.currentTimeMillis()
        val job = buildJob(
            targetPrinter, type, payload, documentName.ifBlank { "documento-${now}" }, copies,
            externalDocumentId, (maxAttempts ?: settingsRepository.current().maxAttempts), now, sourceApp,
        )

        duplicateGuard.remember(debounceKey, job)
        printJobRepository.insert(job)
        logCreated(job, payloadBytes)
        queueDispatcher.requestProcessing()
        return job
    }

    /**
     * Quantidade pedida pelo operador ("5 etiquetas"): cria [quantity] jobs independentes, cada um
     * com 1 cópia e nome "Documento (i/N)" — cada etiqueta tem o seu status, pode ser removida
     * sozinha (as outras seguem valendo) e aparece no histórico. Não é o `copies` da impressora.
     *
     * Idempotência: o duplo-toque é barrado no PEDIDO inteiro (mesma impressora + conteúdo +
     * quantidade em 2 s devolve os jobs já criados); nunca uma etiqueta contra a outra do mesmo
     * pedido, que é quantidade legítima.
     */
    suspend fun enqueueQuantity(
        printerId: String?,
        type: PrintPayloadType,
        payload: String,
        documentName: String,
        quantity: Int,
        externalDocumentId: String? = null,
        sourceApp: String? = null,
    ): List<PrintJob> {
        val licenseStatus = evaluateLicenseStatus()
        if (licenseStatus != EffectiveLicenseStatus.OPERATING) {
            throw LicenseRestrictedException(licenseStatus.name)
        }
        if (payload.isBlank()) throw EmptyPayloadException()
        val payloadBytes = payload.toByteArray(type.wireCharset).size
        val maxBytes = PrintJob.maxPayloadBytesFor(type)
        if (payloadBytes > maxBytes) throw PayloadTooLargeException(payloadBytes, maxBytes)
        if (quantity < 1 || quantity > PrintJob.MAX_COPIES) throw InvalidCopiesException(quantity, PrintJob.MAX_COPIES)

        val targetPrinter = if (printerId != null) {
            printerRepository.getById(printerId)
        } else {
            printerRepository.getDefault()
        } ?: throw NoDefaultPrinterException()

        val requestKey = "qty:${targetPrinter.id}:$documentName:$quantity:${payload.hashCode()}"
        duplicateGuard.previousBatchIfDuplicate(requestKey)?.let { previous ->
            Logger.event(LogLevel.WARN, "job_batch_debounced", printerId = targetPrinter.id, extra = mapOf("quantity" to quantity))
            return previous
        }

        val now = System.currentTimeMillis()
        val attempts = settingsRepository.current().maxAttempts
        val name = documentName.ifBlank { "documento-${now}" }
        // createdAt crescente preserva a ordem 1..N na fila (ordenada por createdAt).
        val jobs = (1..quantity).map { i ->
            val label = if (quantity == 1) name else "$name ($i/$quantity)"
            buildJob(targetPrinter, type, payload, label, 1, externalDocumentId, attempts, now + i - 1, sourceApp)
        }
        duplicateGuard.rememberBatch(requestKey, jobs)
        jobs.forEach {
            printJobRepository.insert(it)
            logCreated(it, payloadBytes)
        }
        queueDispatcher.requestProcessing()
        return jobs
    }

    private fun buildJob(
        targetPrinter: Printer,
        type: PrintPayloadType,
        payload: String,
        documentName: String,
        copies: Int,
        externalDocumentId: String?,
        maxAttempts: Int,
        now: Long,
        sourceApp: String?,
    ): PrintJob {
        // P1.3 — validação ZPL NÃO bloqueante na entrada: detecta estrutura suspeita (^XA/^XZ e
        // afins) sem recusar o job. O ZPL tem dialetos demais para uma heurística ser autoridade;
        // o payload segue para a impressora que é quem decide de verdade, e os avisos viajam com
        // o job para o operador ver na fila/histórico antes de confiar no "Enviado".
        val warnings = if (type == PrintPayloadType.ZPL) ZplValidator.validate(payload) else emptyList()
        return PrintJob(
            id = UUID.randomUUID().toString(),
            printerId = targetPrinter.id,
            type = type,
            payload = payload,
            documentName = documentName,
            copies = copies.coerceAtLeast(1),
            status = PrintJobStatus.PENDING,
            attempts = 0,
            maxAttempts = maxAttempts.coerceAtLeast(1),
            errorMessage = null,
            externalDocumentId = externalDocumentId,
            originalJobId = null,
            createdAt = now,
            startedAt = null,
            sentAt = null,
            updatedAt = now,
            validationWarnings = warnings.map { it.message },
            printerName = targetPrinter.name,
            printerAddress = "${targetPrinter.ipAddress}:${targetPrinter.port}",
            printerProtocol = targetPrinter.protocol.label,
        ).withOrigin(targetPrinter, sourceApp)
    }

    /** Origem do job (§24): a loja é a da impressora de destino; aparelho e coletor vêm do [jobOrigin]. */
    private fun PrintJob.withOrigin(printer: Printer, sourceApp: String?) = copy(
        storeId = printer.storeId,
        collectorId = jobOrigin.collectorId(),
        deviceId = jobOrigin.deviceId(),
        sourceApp = sourceApp,
    )

    private fun logCreated(job: PrintJob, payloadBytes: Int) {
        Logger.event(LogLevel.INFO, "job_created", jobId = job.id, printerId = job.printerId, extra = mapOf("type" to job.type.name, "sizeBytes" to payloadBytes))
        if (job.validationWarnings.isNotEmpty()) {
            Logger.event(
                LogLevel.WARN,
                "job_zpl_warnings",
                jobId = job.id,
                printerId = job.printerId,
                extra = mapOf("warnings" to job.validationWarnings),
            )
        }
    }

    /**
     * Documento multipágina (PDF rasterizado): uma página = um job, todos com o mesmo `groupId` e
     * o mesmo `createdAt`, ordenados por `pageIndex`. Mesmo portão de licença/tamanho/cópias de
     * [invoke], aplicado a TODAS as páginas antes de gravar qualquer uma — ou o documento entra
     * inteiro na fila, ou não entra.
     *
     * Documento de 1 página cai no caminho comum de [invoke] (job avulso, sem grupo).
     * Sem debounce de duplo-toque aqui: é chamada programática (API local), onde mandar o mesmo
     * documento duas vezes é intenção do chamador, não toque duplo do operador.
     */
    suspend fun enqueuePages(
        printerId: String?,
        type: PrintPayloadType,
        pages: List<String>,
        documentName: String,
        copies: Int = 1,
        externalDocumentId: String? = null,
        sourceApp: String? = null,
    ): List<PrintJob> {
        require(pages.isNotEmpty()) { "Documento sem páginas" }
        if (pages.size == 1) {
            return listOf(invoke(printerId, type, pages.single(), documentName, copies, externalDocumentId, sourceApp = sourceApp))
        }

        val licenseStatus = evaluateLicenseStatus()
        if (licenseStatus != EffectiveLicenseStatus.OPERATING) {
            throw LicenseRestrictedException(licenseStatus.name)
        }
        pages.forEach { page ->
            if (page.isBlank()) throw EmptyPayloadException()
            val size = page.toByteArray(type.wireCharset).size
            val maxBytes = PrintJob.maxPayloadBytesFor(type)
            if (size > maxBytes) throw PayloadTooLargeException(size, maxBytes)
        }
        if (copies > PrintJob.MAX_COPIES) throw InvalidCopiesException(copies, PrintJob.MAX_COPIES)

        val targetPrinter = (if (printerId != null) printerRepository.getById(printerId) else printerRepository.getDefault())
            ?: throw NoDefaultPrinterException()

        val now = System.currentTimeMillis()
        val groupId = UUID.randomUUID().toString()
        val maxAttempts = settingsRepository.current().maxAttempts.coerceAtLeast(1)
        val name = documentName.ifBlank { "documento-$now" }
        val jobs = pages.mapIndexed { index, page ->
            PrintJob(
                id = UUID.randomUUID().toString(),
                printerId = targetPrinter.id,
                type = type,
                payload = page,
                documentName = "$name (${index + 1}/${pages.size})",
                copies = copies.coerceAtLeast(1),
                status = PrintJobStatus.PENDING,
                attempts = 0,
                maxAttempts = maxAttempts,
                errorMessage = null,
                externalDocumentId = externalDocumentId,
                originalJobId = null,
                createdAt = now,
                startedAt = null,
                sentAt = null,
                updatedAt = now,
                groupId = groupId,
                pageIndex = index,
                pageCount = pages.size,
                printerName = targetPrinter.name,
                printerAddress = "${targetPrinter.ipAddress}:${targetPrinter.port}",
                printerProtocol = targetPrinter.protocol.label,
            ).withOrigin(targetPrinter, sourceApp)
        }
        printJobRepository.insertAll(jobs)
        Logger.event(
            LogLevel.INFO,
            "document_created",
            jobId = groupId,
            printerId = targetPrinter.id,
            extra = mapOf("type" to type.name, "pages" to pages.size),
        )
        queueDispatcher.requestProcessing()
        return jobs
    }
}

/** Cria um trabalho NOVO (cópia de um antigo) — sujeito ao mesmo portão de licença de [EnqueuePrintJobUseCase], pela mesma razão: é uma linha nova, não a continuação da antiga. */
class ReprintJobUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
    private val queueDispatcher: QueueDispatcher,
    private val evaluateLicenseStatus: EvaluateLicenseStatusUseCase,
) {
    suspend operator fun invoke(originalJobId: String): PrintJob {
        val licenseStatus = evaluateLicenseStatus()
        if (licenseStatus != EffectiveLicenseStatus.OPERATING) {
            throw LicenseRestrictedException(licenseStatus.name)
        }

        val original = printJobRepository.getById(originalJobId)
            ?: throw br.com.atos.printbridge.domain.error.PrintJobNotFoundException(originalJobId)
        val now = System.currentTimeMillis()
        val newJob = original.copy(
            id = UUID.randomUUID().toString(),
            status = PrintJobStatus.PENDING,
            attempts = 0,
            errorMessage = null,
            originalJobId = original.id,
            createdAt = now,
            startedAt = null,
            sentAt = null,
            updatedAt = now,
            // Reimpressão de uma página é um job avulso: se herdasse o groupId, reabriria o status
            // de um documento que já terminou.
            groupId = null,
            pageIndex = null,
            pageCount = null,
        )
        printJobRepository.insert(newJob)
        Logger.event(LogLevel.INFO, "job_reprinted", jobId = newJob.id, printerId = newJob.printerId, extra = mapOf("originalJobId" to originalJobId))
        queueDispatcher.requestProcessing()
        return newJob
    }
}

class CancelPrintJobUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
) {
    suspend operator fun invoke(jobId: String) {
        printJobRepository.cancel(jobId)
        Logger.event(LogLevel.INFO, "job_cancelled", jobId = jobId)
    }
}

/**
 * "Remover" do operador: tira UM job da fila (PENDING/FAILED) sem apagar a linha — ele fica no
 * histórico como DELETED_BY_OPERATOR. Os demais jobs do mesmo pedido continuam valendo. Um job
 * que já está sendo enviado não é removido (os bytes podem já estar na impressora).
 */
class DeletePrintJobByOperatorUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
) {
    suspend operator fun invoke(jobId: String): Boolean {
        val removed = printJobRepository.deleteByOperator(jobId)
        Logger.event(LogLevel.INFO, if (removed) "job_deleted_by_operator" else "job_delete_refused", jobId = jobId)
        return removed
    }
}

class RetryPrintJobUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
    private val queueDispatcher: QueueDispatcher,
) {
    suspend operator fun invoke(jobId: String) {
        val job = printJobRepository.getById(jobId)
            ?: throw br.com.atos.printbridge.domain.error.PrintJobNotFoundException(jobId)
        printJobRepository.update(
            job.copy(
                status = PrintJobStatus.PENDING,
                attempts = 0,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        Logger.event(LogLevel.INFO, "job_manual_retry", jobId = jobId, printerId = job.printerId)
        queueDispatcher.requestProcessing()
    }
}

class RecoverOrphanedJobsUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
) {
    /** Jobs presos em PROCESSING (app morto/kill em meio ao envio) voltam a PENDING no boot. */
    suspend operator fun invoke(): Int {
        val orphaned = printJobRepository.findOrphanedProcessing()
        orphaned.forEach { job ->
            printJobRepository.update(
                job.copy(status = PrintJobStatus.PENDING, updatedAt = System.currentTimeMillis())
            )
            Logger.event(LogLevel.WARN, "job_recovered_from_orphan", jobId = job.id, printerId = job.printerId)
        }
        return orphaned.size
    }
}

/**
 * Aplica `AppSettings.historyRetentionDays` de verdade — antes desta versão, o valor era
 * configurável e exibido na UI, mas nunca aplicado automaticamente; só existia uma purga manual
 * completa (`SettingsViewModel`, `Long.MAX_VALUE`). Chamado a cada execução do
 * `PrintQueueWorker`, então o histórico nunca cresce sem limite entre uma configuração e a
 * próxima (CARREFOUR-PRINT-DATA-CLASSIFICATION.md — retenção de payloads).
 */
class PurgeExpiredHistoryUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()) {
        val retentionDays = settingsRepository.current().historyRetentionDays
        if (retentionDays <= 0) return // 0 ou negativo = retenção desabilitada, nunca purga automaticamente
        val cutoff = nowMillis - retentionDays * 24L * 60 * 60 * 1000
        printJobRepository.purgeHistoryOlderThan(cutoff)
    }
}

class ObserveActivePrintJobsUseCase @Inject constructor(
    private val repository: PrintJobRepository,
) {
    operator fun invoke() = repository.observeActive()
}

class ObservePrintJobHistoryUseCase @Inject constructor(
    private val repository: PrintJobRepository,
) {
    operator fun invoke() = repository.observeHistory()
}
