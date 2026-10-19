package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidCopiesException
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.queue.JobOriginProvider
import br.com.atos.printbridge.domain.queue.JobSource
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras de fila do PROMPT MASTER (§21–23): quantidade legítima nunca é bloqueada pela
 * idempotência; o duplo-toque é; e o operador remove jobs um a um sem invalidar os outros.
 */
class QueueQuantityAndDeleteTest {

    private val printer = Printer(
        id = "tsc-1",
        storeId = null,
        name = "TSC MB241",
        model = PrinterModel.TSC_MB241,
        ipAddress = "10.10.40.32",
        port = 9100,
        protocol = PrinterProtocol.TCP,
        language = PrinterLanguage.TSPL,
        isDefault = true,
        enabled = true,
        lastSeenAt = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private val jobs = FakePrintJobRepository()
    private val dispatcher = FakeQueueDispatcher()
    private val enqueue = EnqueuePrintJobUseCase(
        jobs,
        FakePrinterRepository().apply { seed(printer) },
        DuplicateSubmissionGuard(),
        dispatcher,
        EvaluateLicenseStatusUseCase(
            FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
            TestLicenseTokenSigner.createVerifier(),
            TestLicenseTokenSigner.deviceIdentity(),
        ),
        FakeSettingsRepository(),
    )
    private val delete = DeletePrintJobByOperatorUseCase(jobs)

    private suspend fun fiveLabels() =
        enqueue.enqueueQuantity(null, PrintPayloadType.TSPL, "SIZE 100 mm,150 mm\r\nPRINT 1\r\n", "Etiqueta SIF", 5)

    @Test
    fun `five labels requested are five independent jobs of one copy each`() = runTest {
        val created = fiveLabels()

        assertEquals(5, created.size)
        assertEquals(5, jobs.snapshot.size)
        assertEquals(5, created.map { it.id }.toSet().size)
        assertTrue(created.all { it.copies == 1 && it.status == PrintJobStatus.PENDING })
        assertEquals((1..5).map { "Etiqueta SIF ($it/5)" }, created.map { it.documentName })
        // Ordem 1..5 preservada na fila (ordenada por createdAt).
        assertEquals(created.map { it.id }, created.sortedBy { it.createdAt }.map { it.id })
    }

    @Test
    fun `double tap on the same request does not duplicate the labels`() = runTest {
        val first = fiveLabels()
        val second = fiveLabels()

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(5, jobs.snapshot.size)
    }

    @Test
    fun `a different quantity is a new intent, not a duplicate`() = runTest {
        fiveLabels()
        enqueue.enqueueQuantity(null, PrintPayloadType.TSPL, "SIZE 100 mm,150 mm\r\nPRINT 1\r\n", "Etiqueta SIF", 3)

        assertEquals(8, jobs.snapshot.size)
    }

    @Test
    fun `quantity out of range is refused`() {
        assertThrows(InvalidCopiesException::class.java) {
            kotlinx.coroutines.runBlocking { enqueue.enqueueQuantity(null, PrintPayloadType.TSPL, "PRINT 1", "x", 0) }
        }
        assertThrows(InvalidCopiesException::class.java) {
            kotlinx.coroutines.runBlocking { enqueue.enqueueQuantity(null, PrintPayloadType.TSPL, "PRINT 1", "x", 101) }
        }
    }

    @Test
    fun `operator removes jobs 1 and 2 and jobs 3 4 5 stay valid while removed ones stay in history`() = runTest {
        val created = fiveLabels()

        assertTrue(delete(created[0].id))
        assertTrue(delete(created[1].id))

        val byId = jobs.snapshot.associateBy { it.id }
        assertEquals(PrintJobStatus.DELETED_BY_OPERATOR, byId.getValue(created[0].id).status)
        assertEquals(PrintJobStatus.DELETED_BY_OPERATOR, byId.getValue(created[1].id).status)
        assertEquals(listOf(PrintJobStatus.PENDING, PrintJobStatus.PENDING, PrintJobStatus.PENDING), created.drop(2).map { byId.getValue(it.id).status })
        // Nada foi apagado: os removidos continuam registrados.
        assertEquals(5, jobs.snapshot.size)
        // O próximo a imprimir é o 3.
        assertEquals(created[2].id, jobs.nextPendingFor(printer.id)?.id)
    }

    @Test
    fun `a job already being sent cannot be removed`() = runTest {
        val job = fiveLabels().first()
        jobs.update(job.copy(status = PrintJobStatus.PROCESSING))

        assertFalse(delete(job.id))
        assertEquals(PrintJobStatus.PROCESSING, jobs.getById(job.id)?.status)
    }

    @Test
    fun `every job records store, collector, device and source app`() = runTest {
        val repo = FakePrintJobRepository()
        val origin = object : JobOriginProvider {
            override fun deviceId() = "keystore-abc"
            override fun collectorId() = "LOJA-001-C07"
        }
        val withOrigin = EnqueuePrintJobUseCase(
            repo,
            FakePrinterRepository().apply { seed(printer.copy(storeId = "LOJA-001")) },
            DuplicateSubmissionGuard(),
            FakeQueueDispatcher(),
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            FakeSettingsRepository(),
            origin,
        )

        val single = withOrigin(null, PrintPayloadType.TSPL, "PRINT 1", "avulso", sourceApp = JobSource.externalApp("com.athos.integracaoa"))
        val batch = withOrigin.enqueueQuantity(null, PrintPayloadType.TSPL, "PRINT 1", "lote", 2, sourceApp = JobSource.APP_LABELS)

        (listOf(single) + batch).forEach {
            assertEquals("LOJA-001", it.storeId)
            assertEquals("LOJA-001-C07", it.collectorId)
            assertEquals("keystore-abc", it.deviceId)
        }
        assertEquals("api:com.athos.integracaoa", single.sourceApp)
        assertEquals(listOf(JobSource.APP_LABELS, JobSource.APP_LABELS), batch.map { it.sourceApp })
        // Sem login de operador ainda: nunca inventado.
        assertNull(single.operatorName)
    }

    @Test
    fun `reprint of a removed label is a new intent and creates a new job`() = runTest {
        val job = fiveLabels().first()
        delete(job.id)

        val reprint = ReprintJobUseCase(
            jobs,
            dispatcher,
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
        )(job.id)

        assertEquals(PrintJobStatus.PENDING, reprint.status)
        assertEquals(job.id, reprint.originalJobId)
        assertEquals(6, jobs.snapshot.size)
    }
}
