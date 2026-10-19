package br.com.atos.printbridge.domain.queue

import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFailuresTest {

    private val now = 1_790_300_000_000L
    private val hour = 60L * 60 * 1000
    private val printers = setOf("p")

    private fun job(
        status: PrintJobStatus,
        updatedAt: Long,
        documentName: String = "d-$status-$updatedAt",
        printerId: String = "p",
        id: String = "$status-$updatedAt-$documentName",
        originalJobId: String? = null,
    ) = PrintJob(
        id = id, printerId = printerId, type = PrintPayloadType.ZPL, payload = "^XA^XZ", documentName = documentName,
        copies = 1, status = status, attempts = 5, maxAttempts = 5, errorMessage = "Conexão recusada",
        externalDocumentId = null, originalJobId = originalJobId, createdAt = updatedAt, startedAt = null, sentAt = null, updatedAt = updatedAt,
    )

    @Test
    fun `only failures of the last 24 hours count, older ones stay in history only`() {
        val jobs = listOf(
            job(PrintJobStatus.FAILED, now - 1 * hour),
            job(PrintJobStatus.FAILED, now - 23 * hour),
            job(PrintJobStatus.FAILED, now - 25 * hour), // teste de fumaça de ontem
            job(PrintJobStatus.FAILED, now - 48 * hour),
            job(PrintJobStatus.SENT, now - 1 * hour),
            job(PrintJobStatus.CANCELLED, now - 1 * hour),
        )
        assertEquals(2, RecentFailures.count(jobs, now, printers))
        assertEquals(0, RecentFailures.count(emptyList(), now, printers))
    }

    @Test
    fun `falha resolvida por impressao posterior do mesmo documento sai do contador`() {
        val jobs = listOf(
            job(PrintJobStatus.FAILED, now - 3 * hour, documentName = "Etiqueta 40x30"),
            job(PrintJobStatus.SENT, now - 2 * hour, documentName = "Etiqueta 40x30", printerId = "outra"),
        )
        assertEquals(0, RecentFailures.count(jobs, now, printers + "outra"))
    }

    @Test
    fun `sucesso anterior a falha nao resolve a falha`() {
        val jobs = listOf(
            job(PrintJobStatus.SENT, now - 3 * hour, documentName = "Etiqueta 40x30"),
            job(PrintJobStatus.FAILED, now - 2 * hour, documentName = "Etiqueta 40x30"),
        )
        assertEquals(1, RecentFailures.count(jobs, now, printers))
    }

    @Test
    fun `reimpressao bem sucedida resolve a falha original`() {
        val jobs = listOf(
            job(PrintJobStatus.FAILED, now - 3 * hour, id = "falhou"),
            job(PrintJobStatus.SENT, now - 2 * hour, originalJobId = "falhou"),
        )
        assertEquals(0, RecentFailures.count(jobs, now, printers))
    }

    @Test
    fun `falha de impressora removida nao pede acao`() {
        val jobs = listOf(
            job(PrintJobStatus.FAILED, now - 1 * hour, printerId = "simulada-removida"),
            job(PrintJobStatus.FAILED, now - 1 * hour, printerId = "p"),
        )
        assertEquals(1, RecentFailures.count(jobs, now, printers))
    }
}
