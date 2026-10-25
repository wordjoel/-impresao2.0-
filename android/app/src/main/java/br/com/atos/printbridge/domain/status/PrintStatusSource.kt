package br.com.atos.printbridge.domain.status

import br.com.atos.printbridge.domain.model.Printer
import kotlinx.coroutines.flow.Flow

/** Leituras que montam o [PrintStatusSnapshot]. Só contagens e horários: nada de payload ou operador. */
interface PrintStatusSource {
    suspend fun printers(): List<Printer>
    suspend fun queueSummary(since: Long): QueueSummaryRow
    suspend fun printerActivity(): List<PrinterActivityRow>
    suspend fun recentFailures(since: Long): List<JobFailureRow>

    /** Emite a cada mudança na fila. */
    fun changes(): Flow<Long?>
}

data class QueueSummaryRow(
    val pending: Int,
    val processing: Int,
    val oldestPendingAt: Long?,
    val failedSince: Int,
    val sentSince: Int,
)

data class PrinterActivityRow(
    val printerId: String,
    val activeJobs: Int,
    val lastSentAt: Long?,
    val lastFailedAt: Long?,
)

data class JobFailureRow(
    val printerId: String,
    val errorMessage: String?,
    val updatedAt: Long,
)

/** Junta impressoras cadastradas, atividade e falhas no retrato que vai ao MDM. */
object PrintStatusBuilder {
    const val WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    suspend fun build(source: PrintStatusSource, now: Long): PrintStatusSnapshot {
        val since = now - WINDOW_MILLIS
        val queue = source.queueSummary(since)
        val activity = source.printerActivity().associateBy { it.printerId }
        // A lista vem do mais recente para o mais antigo: o primeiro de cada impressora é o último erro.
        val lastError = linkedMapOf<String, String?>()
        source.recentFailures(since).forEach { if (it.printerId !in lastError) lastError[it.printerId] = it.errorMessage }
        val printers = source.printers().map { p ->
            val a = activity[p.id]
            PrinterStatus(
                name = p.name,
                address = "${p.ipAddress}:${p.port}",
                enabled = p.enabled,
                isDefault = p.isDefault,
                activeJobs = a?.activeJobs ?: 0,
                lastSentAt = a?.lastSentAt,
                lastFailedAt = a?.lastFailedAt,
                lastError = lastError[p.id],
            )
        }
        return PrintStatusSnapshot(
            generatedAt = now,
            pending = queue.pending,
            processing = queue.processing,
            oldestPendingAgeSeconds = queue.oldestPendingAt?.let { ((now - it) / 1000).coerceAtLeast(0) },
            failedLast24h = queue.failedSince,
            sentLast24h = queue.sentSince,
            printers = printers,
        )
    }
}
