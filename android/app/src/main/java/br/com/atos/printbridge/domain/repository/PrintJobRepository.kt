package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.PrintJob
import kotlinx.coroutines.flow.Flow

interface PrintJobRepository {
    // observeActive, observeHistory e getByGroupId vêm SEM payload (lista/status): para enviar,
    // reimprimir ou gravar um job, leia-o por getById.
    fun observeActive(): Flow<List<PrintJob>>
    fun observeHistory(): Flow<List<PrintJob>>
    suspend fun getById(id: String): PrintJob?
    suspend fun insert(job: PrintJob)
    suspend fun update(job: PrintJob)
    suspend fun nextPendingFor(printerId: String): PrintJob?
    suspend fun findOrphanedProcessing(): List<PrintJob>
    suspend fun cancel(id: String)

    /** Marca DELETED_BY_OPERATOR (só PENDING/FAILED; enviando não). true = removido. */
    suspend fun deleteByOperator(id: String): Boolean
    suspend fun purgeHistoryOlderThan(timestampMillis: Long)
    suspend fun printerIdsWithPendingJobs(): List<String>

    /** Insere as páginas de um documento de uma vez (tudo ou nada). */
    suspend fun insertAll(jobs: List<PrintJob>)
    suspend fun getByGroupId(groupId: String): List<PrintJob>

    /** Jobs ativos à frente de [job] na fila da mesma impressora (0 = é o próximo). */
    suspend fun countActiveAhead(job: PrintJob): Int
}
