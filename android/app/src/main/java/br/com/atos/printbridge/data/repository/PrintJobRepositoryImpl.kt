package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.PrintJobDao
import br.com.atos.printbridge.data.mapper.toDomain
import br.com.atos.printbridge.data.mapper.toEntity
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PrintJobRepositoryImpl @Inject constructor(
    private val dao: PrintJobDao,
) : PrintJobRepository {

    override fun observeActive(): Flow<List<PrintJob>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeHistory(): Flow<List<PrintJob>> =
        dao.observeHistory().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): PrintJob? = dao.getById(id)?.toDomain()

    override suspend fun insert(job: PrintJob) = dao.insert(job.toEntity())

    override suspend fun update(job: PrintJob) = dao.update(job.toEntity())

    override suspend fun nextPendingFor(printerId: String): PrintJob? =
        dao.nextPendingFor(printerId)?.toDomain()

    override suspend fun findOrphanedProcessing(): List<PrintJob> =
        dao.findOrphanedProcessing().map { it.toDomain() }

    override suspend fun cancel(id: String) = dao.cancel(id, System.currentTimeMillis())
    override suspend fun deleteByOperator(id: String): Boolean = dao.deleteByOperator(id, System.currentTimeMillis()) > 0

    override suspend fun purgeHistoryOlderThan(timestampMillis: Long) =
        dao.purgeHistoryOlderThan(timestampMillis)

    override suspend fun printerIdsWithPendingJobs(): List<String> = dao.printerIdsWithPendingJobs()

    override suspend fun insertAll(jobs: List<PrintJob>) = dao.insertAll(jobs.map { it.toEntity() })

    override suspend fun getByGroupId(groupId: String): List<PrintJob> =
        dao.getByGroupId(groupId).map { it.toDomain() }

    override suspend fun countActiveAhead(job: PrintJob): Int =
        dao.countActiveAhead(job.printerId, job.createdAt, job.pageIndex ?: 0)
}
