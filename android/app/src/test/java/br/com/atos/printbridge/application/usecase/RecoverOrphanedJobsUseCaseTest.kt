package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prova automatizado da recuperação de órfãos (CARREFOUR-CYBER-SECURITY-GATE.md H3): jobs presos em
 * PROCESSING (processo morto no meio do envio) voltam a PENDING na inicialização, para serem
 * processados de novo — sem gastar tentativa extra, sem cancelar e sem tocar em jobs de outros
 * estados. O mecanismo roda no boot do app (BootCompletedReceiver/WorkManager) e nunca teve teste
 * próprio no Android (o Windows já cobre o equivalente via QueueSurvivesRestartTests.cs).
 */
class RecoverOrphanedJobsUseCaseTest {

    private fun job(id: String, status: PrintJobStatus, attempts: Int = 1) = PrintJob(
        id = id,
        printerId = "printer-1",
        type = PrintPayloadType.ZPL,
        payload = "^XA^XZ",
        documentName = "etiqueta",
        copies = 1,
        status = status,
        attempts = attempts,
        maxAttempts = 3,
        errorMessage = null,
        externalDocumentId = null,
        originalJobId = null,
        createdAt = 1_000L,
        startedAt = 1_500L,
        sentAt = null,
        updatedAt = 1_500L,
    )

    @Test
    fun processing_orphans_are_returned_to_pending_and_others_are_untouched() = runTest {
        val repo = FakePrintJobRepository()
        repo.insert(job("orphan-a", PrintJobStatus.PROCESSING))
        repo.insert(job("orphan-b", PrintJobStatus.PROCESSING))
        repo.insert(job("pending-c", PrintJobStatus.PENDING, attempts = 0))
        repo.insert(job("sent-d", PrintJobStatus.SENT))
        repo.insert(job("failed-e", PrintJobStatus.FAILED))

        val recovered = RecoverOrphanedJobsUseCase(repo).invoke()

        assertEquals(2, recovered)
        assertEquals(PrintJobStatus.PENDING, repo.getById("orphan-a")!!.status)
        assertEquals(PrintJobStatus.PENDING, repo.getById("orphan-b")!!.status)
        assertEquals(PrintJobStatus.PENDING, repo.getById("pending-c")!!.status)
        assertEquals(PrintJobStatus.SENT, repo.getById("sent-d")!!.status)
        assertEquals(PrintJobStatus.FAILED, repo.getById("failed-e")!!.status)
    }

    @Test
    fun orphan_recovery_does_not_reset_attempts_or_copies() = runTest {
        val repo = FakePrintJobRepository()
        repo.insert(job("orphan-a", PrintJobStatus.PROCESSING, attempts = 2))

        RecoverOrphanedJobsUseCase(repo).invoke()

        val recovered = repo.getById("orphan-a")!!
        assertEquals(PrintJobStatus.PENDING, recovered.status)
        // A tentativa que já foi consumida quando o processo morreu NÃO é zerada — o retry
        // seguinte continua contando a partir dela, em vez de ganhar tentativas "de graça".
        assertEquals(2, recovered.attempts)
        assertEquals(1, recovered.copies)
    }

    @Test
    fun no_processing_jobs_returns_zero() = runTest {
        val repo = FakePrintJobRepository()
        repo.insert(job("sent-a", PrintJobStatus.SENT))

        val recovered = RecoverOrphanedJobsUseCase(repo).invoke()

        assertEquals(0, recovered)
    }
}