package br.com.atos.printbridge.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase
import br.com.atos.printbridge.application.usecase.PurgeExpiredHistoryUseCase
import br.com.atos.printbridge.application.usecase.RecoverOrphanedJobsUseCase
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Uma corrotina por impressora com job pendente — impressoras diferentes processam em paralelo,
 * a mesma impressora nunca em paralelo consigo mesma (garantido pelo Mutex em
 * ProcessPrintQueueUseCase/PrinterQueueMutexProvider, que é @Singleton e sobrevive entre execuções
 * deste Worker).
 */
@HiltWorker
class PrintQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recoverOrphanedJobsUseCase: RecoverOrphanedJobsUseCase,
    private val processPrintQueueUseCase: ProcessPrintQueueUseCase,
    private val purgeExpiredHistoryUseCase: PurgeExpiredHistoryUseCase,
    private val printJobRepository: PrintJobRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        Logger.event(LogLevel.INFO, "queue_worker_started")
        try {
            val recovered = recoverOrphanedJobsUseCase()
            if (recovered > 0) {
                Logger.event(LogLevel.INFO, "queue_worker_recovered_orphans", extra = mapOf("count" to recovered))
            }

            purgeExpiredHistoryUseCase()

            val printerIds = printJobRepository.printerIdsWithPendingJobs()
            printerIds.map { printerId ->
                async { processPrintQueueUseCase.processPrinter(printerId) }
            }.awaitAll()

            Logger.event(LogLevel.INFO, "queue_worker_finished")
            Result.success()
        } catch (e: Exception) {
            Logger.event(LogLevel.ERROR, "queue_worker_failed", extra = mapOf("exception" to (e.message ?: e::class.simpleName)))
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "print_queue_worker"
    }
}
