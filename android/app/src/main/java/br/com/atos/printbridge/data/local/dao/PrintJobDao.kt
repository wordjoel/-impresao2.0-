package br.com.atos.printbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import br.com.atos.printbridge.data.local.entity.PrintJobEntity
import br.com.atos.printbridge.domain.status.JobFailureRow
import br.com.atos.printbridge.domain.status.PrinterActivityRow
import br.com.atos.printbridge.domain.status.QueueSummaryRow
import kotlinx.coroutines.flow.Flow

/**
 * Todas as colunas de print_jobs, com o payload VAZIO. Uma página rasterizada (BITMAP TSPL / ^GFA
 * 100x150 a 300 dpi) tem centenas de KB, e o CursorWindow do Android tem 2 MB: listar várias delas
 * com `SELECT *` derrubava o app (CF550, 29/09/2026). Quem precisa enviar ou copiar o job lê a
 * linha completa por `getById`/`nextPendingFor` — nunca grave (update) um job vindo destas listas.
 */
private const val COLUMNS_WITHOUT_PAYLOAD =
    "id, printerId, type, '' AS payload, documentName, copies, status, attempts, maxAttempts, errorMessage, " +
        "externalDocumentId, originalJobId, createdAt, startedAt, sentAt, updatedAt, validationWarnings, " +
        "groupId, pageIndex, pageCount, printerName, printerAddress, printerProtocol, " +
        "storeId, collectorId, deviceId, sourceApp, operatorName"

@Dao
interface PrintJobDao {
    /** Sem payload (ver [COLUMNS_WITHOUT_PAYLOAD]). */
    @Query("SELECT $COLUMNS_WITHOUT_PAYLOAD FROM print_jobs WHERE status IN ('PENDING', 'PROCESSING') ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<PrintJobEntity>>

    /** Sem payload (ver [COLUMNS_WITHOUT_PAYLOAD]). */
    @Query("SELECT $COLUMNS_WITHOUT_PAYLOAD FROM print_jobs WHERE status IN ('SENT', 'FAILED', 'CANCELLED', 'DELETED_BY_OPERATOR') ORDER BY updatedAt DESC")
    fun observeHistory(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PrintJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: PrintJobEntity)

    @Update
    suspend fun update(job: PrintJobEntity)

    // pageIndex desempata páginas de um mesmo documento (inseridas no mesmo instante): a página 2
    // nunca sai antes da 1, mesmo que a 1 tenha voltado a PENDING para retry.
    @Query(
        "SELECT * FROM print_jobs WHERE printerId = :printerId AND status = 'PENDING' " +
            "ORDER BY createdAt ASC, COALESCE(pageIndex, 0) ASC LIMIT 1"
    )
    suspend fun nextPendingFor(printerId: String): PrintJobEntity?

    /** Todas as páginas de um documento, na ordem de impressão. Transação: nenhuma página fica de fora. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(jobs: List<PrintJobEntity>)

    /** Só para status do documento; sem payload (ver [COLUMNS_WITHOUT_PAYLOAD]). */
    @Query("SELECT $COLUMNS_WITHOUT_PAYLOAD FROM print_jobs WHERE groupId = :groupId ORDER BY pageIndex ASC")
    suspend fun getByGroupId(groupId: String): List<PrintJobEntity>

    /** Quantos jobs ativos estão à frente deste na fila da mesma impressora. */
    @Query(
        "SELECT COUNT(*) FROM print_jobs WHERE printerId = :printerId AND status IN ('PENDING', 'PROCESSING') " +
            "AND (createdAt < :createdAt OR (createdAt = :createdAt AND COALESCE(pageIndex, 0) < :pageIndex))"
    )
    suspend fun countActiveAhead(printerId: String, createdAt: Long, pageIndex: Int): Int

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status IN ('PENDING', 'PROCESSING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM print_jobs WHERE status = 'PROCESSING'")
    suspend fun findOrphanedProcessing(): List<PrintJobEntity>

    @Query("UPDATE print_jobs SET status = 'CANCELLED', updatedAt = :now WHERE id = :id AND status IN ('PENDING', 'FAILED')")
    suspend fun cancel(id: String, now: Long)

    @Query("UPDATE print_jobs SET status = 'DELETED_BY_OPERATOR', updatedAt = :now WHERE id = :id AND status IN ('PENDING', 'FAILED')")
    suspend fun deleteByOperator(id: String, now: Long): Int

    @Query("DELETE FROM print_jobs WHERE status IN ('SENT', 'FAILED', 'CANCELLED', 'DELETED_BY_OPERATOR') AND updatedAt < :timestamp")
    suspend fun purgeHistoryOlderThan(timestamp: Long)

    @Query("SELECT DISTINCT printerId FROM print_jobs WHERE status = 'PENDING'")
    suspend fun printerIdsWithPendingJobs(): List<String>

    // ---- Estado para o MDM (item 2): só contagens e horários, nunca payload nem operador. ----

    @Query(
        "SELECT COALESCE(SUM(status = 'PENDING'), 0) AS pending, COALESCE(SUM(status = 'PROCESSING'), 0) AS processing, " +
            "MIN(CASE WHEN status = 'PENDING' THEN createdAt END) AS oldestPendingAt, " +
            "COALESCE(SUM(status = 'FAILED' AND updatedAt >= :since), 0) AS failedSince, " +
            "COALESCE(SUM(status = 'SENT' AND COALESCE(sentAt, updatedAt) >= :since), 0) AS sentSince FROM print_jobs"
    )
    suspend fun queueSummary(since: Long): QueueSummaryRow

    @Query(
        "SELECT printerId, COALESCE(SUM(status IN ('PENDING', 'PROCESSING')), 0) AS activeJobs, " +
            "MAX(CASE WHEN status = 'SENT' THEN COALESCE(sentAt, updatedAt) END) AS lastSentAt, " +
            "MAX(CASE WHEN status = 'FAILED' THEN updatedAt END) AS lastFailedAt FROM print_jobs GROUP BY printerId"
    )
    suspend fun printerActivity(): List<PrinterActivityRow>

    @Query("SELECT printerId, errorMessage, updatedAt FROM print_jobs WHERE status = 'FAILED' AND updatedAt >= :since ORDER BY updatedAt DESC LIMIT 200")
    suspend fun recentFailures(since: Long): List<JobFailureRow>

    /** Emite a cada mudança em print_jobs (o Room reavalia a consulta quando a tabela muda). */
    @Query("SELECT MAX(updatedAt) FROM print_jobs")
    fun observeLastChange(): Flow<Long?>
}
