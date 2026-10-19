package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PurgeExpiredHistoryUseCaseTest {

    private fun job(id: String, status: PrintJobStatus, updatedAt: Long) = PrintJob(
        id = id,
        printerId = "printer-1",
        type = PrintPayloadType.ZPL,
        payload = "^XA^XZ",
        documentName = "etiqueta",
        copies = 1,
        status = status,
        attempts = 0,
        maxAttempts = 3,
        errorMessage = null,
        externalDocumentId = null,
        originalJobId = null,
        createdAt = updatedAt,
        startedAt = null,
        sentAt = null,
        updatedAt = updatedAt,
    )

    @Test
    fun `purges history older than the configured retention window`() = runTest {
        val now = 100_000_000L
        val oneDayMillis = 24L * 60 * 60 * 1000
        val jobRepo = FakePrintJobRepository()
        jobRepo.insert(job("old-sent", PrintJobStatus.SENT, updatedAt = now - 31 * oneDayMillis))
        jobRepo.insert(job("recent-sent", PrintJobStatus.SENT, updatedAt = now - 1 * oneDayMillis))
        jobRepo.insert(job("old-pending", PrintJobStatus.PENDING, updatedAt = now - 31 * oneDayMillis))

        val settingsRepo = FakeSettingsRepository(AppSettings(historyRetentionDays = 30))
        val useCase = PurgeExpiredHistoryUseCase(jobRepo, settingsRepo)

        useCase(nowMillis = now)

        assertNull(jobRepo.snapshot.firstOrNull { it.id == "old-sent" })
        assertNotNull(jobRepo.snapshot.firstOrNull { it.id == "recent-sent" })
        // Job ativo (PENDING) nunca é purgado, mesmo antigo — fila continua sendo autoridade.
        assertNotNull(jobRepo.snapshot.firstOrNull { it.id == "old-pending" })
    }

    @Test
    fun `retention of zero or less disables automatic purge`() = runTest {
        val now = 100_000_000L
        val oneDayMillis = 24L * 60 * 60 * 1000
        val jobRepo = FakePrintJobRepository()
        jobRepo.insert(job("very-old-sent", PrintJobStatus.SENT, updatedAt = now - 3650 * oneDayMillis))

        val settingsRepo = FakeSettingsRepository(AppSettings(historyRetentionDays = 0))
        val useCase = PurgeExpiredHistoryUseCase(jobRepo, settingsRepo)

        useCase(nowMillis = now)

        assertEquals(1, jobRepo.snapshot.size)
    }
}
