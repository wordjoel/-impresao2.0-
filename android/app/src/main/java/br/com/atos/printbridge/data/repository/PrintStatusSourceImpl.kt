package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.PrintJobDao
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.status.JobFailureRow
import br.com.atos.printbridge.domain.status.PrinterActivityRow
import br.com.atos.printbridge.domain.status.PrintStatusSource
import br.com.atos.printbridge.domain.status.QueueSummaryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PrintStatusSourceImpl @Inject constructor(
    private val dao: PrintJobDao,
    private val printerRepository: PrinterRepository,
) : PrintStatusSource {
    override suspend fun printers(): List<Printer> = printerRepository.observeAll().first()
    override suspend fun queueSummary(since: Long): QueueSummaryRow = dao.queueSummary(since)
    override suspend fun printerActivity(): List<PrinterActivityRow> = dao.printerActivity()
    override suspend fun recentFailures(since: Long): List<JobFailureRow> = dao.recentFailures(since)
    override fun changes(): Flow<Long?> = dao.observeLastChange()
}
