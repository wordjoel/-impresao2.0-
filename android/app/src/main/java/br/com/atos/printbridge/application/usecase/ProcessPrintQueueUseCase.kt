package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.queue.RetryPolicy
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Um Mutex por impressora garante que nunca haja dois envios TCP simultâneos para o mesmo IP. */
@Singleton
class PrinterQueueMutexProvider @Inject constructor() {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    fun mutexFor(printerId: String): Mutex = mutexes.computeIfAbsent(printerId) { Mutex() }
}

/**
 * Processa toda a fila pendente. Impressoras diferentes são processadas de forma independente
 * pelo chamador (uma corrotina por printerId); dentro de uma mesma impressora, o Mutex garante
 * ordem sequencial estrita. Backoff é limitado por maxAttempts — nunca há loop infinito.
 */
class ProcessPrintQueueUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
    private val printerRepository: PrinterRepository,
    private val settingsRepository: SettingsRepository,
    private val printEngine: PrintEngine,
    private val networkStateProvider: NetworkStateProvider,
    private val mutexProvider: PrinterQueueMutexProvider,
    // Default só para os testes que não observam eventos; em produção o Hilt injeta o singleton.
    private val eventBus: PrintJobEventBus = PrintJobEventBus(),
) {
    suspend fun processPrinter(printerId: String) {
        val mutex = mutexProvider.mutexFor(printerId)
        mutex.withLock {
            while (true) {
                val job = printJobRepository.nextPendingFor(printerId) ?: break
                val printer = printerRepository.getById(printerId) ?: break
                if (!printer.enabled) {
                    Logger.event(LogLevel.WARN, "job_skipped_printer_disabled", jobId = job.id, printerId = printerId)
                    break
                }

                if (!networkStateProvider.isNetworkAvailable()) {
                    // Sem rede afeta TODOS os jobs pendentes desta impressora igualmente — encerra
                    // o processamento desta impressora nesta execução em vez de "continue" (que
                    // re-buscaria o mesmo job PENDING e giraria em loop apertado sem nunca avançar).
                    // O job permanece recuperável: a rede de segurança do WorkManager tenta de novo depois.
                    handleFailure(job, TransportResult.Failure(TransportFailureReason.NO_NETWORK, "Sem rede no dispositivo Android"))
                    break
                }

                val now = System.currentTimeMillis()
                printJobRepository.update(job.copy(status = PrintJobStatus.PROCESSING, startedAt = now, attempts = job.attempts + 1, updatedAt = now))
                Logger.event(LogLevel.INFO, "job_processing_started", jobId = job.id, printerId = printerId, extra = mapOf("attempt" to job.attempts + 1))

                val result = printEngine.send(printer, job.copy(attempts = job.attempts + 1))

                when (result) {
                    is TransportResult.Success -> {
                        val sentAt = System.currentTimeMillis()
                        printJobRepository.update(
                            job.copy(
                                status = PrintJobStatus.SENT,
                                attempts = job.attempts + 1,
                                errorMessage = null,
                                sentAt = sentAt,
                                updatedAt = sentAt,
                            )
                        )
                        Logger.event(LogLevel.INFO, "job_sent", jobId = job.id, printerId = printerId, extra = mapOf("bytes" to result.bytesSent, "durationMs" to result.durationMillis))
                        eventBus.emit(PrintJobEvent.Completed(job.id, job.groupId, durationMillis = sentAt - job.createdAt, attempts = job.attempts + 1))
                        if (job.groupId != null && job.pageIndex != null && job.pageCount != null) {
                            eventBus.emit(PrintJobEvent.PagePrinted(job.id, job.groupId, job.pageIndex + 1, job.pageCount))
                        }
                        emitIfDocumentFinished(job)
                    }
                    is TransportResult.Failure -> {
                        handleFailure(job.copy(attempts = job.attempts + 1), result)
                    }
                }
            }
        }
    }

    private suspend fun handleFailure(job: PrintJob, failure: TransportResult.Failure) {
        val settings = settingsRepository.current()
        val exhausted = job.attempts >= job.maxAttempts
        val now = System.currentTimeMillis()
        val finalStatus = if (exhausted) PrintJobStatus.FAILED else PrintJobStatus.PENDING
        printJobRepository.update(
            job.copy(
                status = finalStatus,
                errorMessage = failure.reason.operatorMessage,
                updatedAt = now,
            )
        )
        Logger.event(
            LogLevel.WARN,
            if (exhausted) "job_failed_final" else "job_failed_will_retry",
            jobId = job.id,
            printerId = job.printerId,
            extra = mapOf("reason" to failure.reason.name, "attempt" to job.attempts, "maxAttempts" to job.maxAttempts),
        )
        if (exhausted) {
            eventBus.emit(PrintJobEvent.Failed(job.id, job.groupId, failure.reason.operatorMessage, job.attempts))
            emitIfDocumentFinished(job)
        } else {
            val wait = if (settings.autoRetryTransientFailures) RetryPolicy.backoffMillis(settings.retryBaseIntervalMillis, job.attempts) else 0L
            eventBus.emit(PrintJobEvent.Retrying(job.id, job.groupId, job.attempts, job.maxAttempts, failure.reason.operatorMessage, wait))
            delay(wait)
        }
    }

    /** Última página de um documento chegou a estado final → avisa o resultado consolidado. */
    private suspend fun emitIfDocumentFinished(job: PrintJob) {
        val groupId = job.groupId ?: return
        val pages = printJobRepository.getByGroupId(groupId)
        if (pages.isEmpty() || pages.any { it.status.isActive }) return
        eventBus.emit(
            PrintJobEvent.DocumentFinished(
                jobId = job.id,
                groupId = groupId,
                pagesOk = pages.count { it.status == PrintJobStatus.SENT },
                pagesTotal = pages.first().pageCount ?: pages.size,
            ),
        )
    }
}
