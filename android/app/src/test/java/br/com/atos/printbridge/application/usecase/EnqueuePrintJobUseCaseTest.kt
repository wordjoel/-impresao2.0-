package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.EmptyPayloadException
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PayloadTooLargeException
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private val defaultPrinter = Printer(
    id = "printer-1",
    storeId = null,
    name = "Expedição 01",
    model = PrinterModel.TSC_MB241,
    ipAddress = "192.168.0.50",
    port = 9100,
    protocol = PrinterProtocol.TCP,
    language = PrinterLanguage.ZPL,
    isDefault = true,
    enabled = true,
    lastSeenAt = null,
    createdAt = 0,
    updatedAt = 0,
)

class EnqueuePrintJobUseCaseTest {

    private fun buildUseCase(
        printerRepo: FakePrinterRepository = FakePrinterRepository().apply { seed(defaultPrinter) },
        jobRepo: FakePrintJobRepository = FakePrintJobRepository(),
        dispatcher: FakeQueueDispatcher = FakeQueueDispatcher(),
        licenseStatus: LicenseTokenStatus = LicenseTokenStatus.ACTIVE,
        licenseValidUntil: Long = Long.MAX_VALUE / 2,
    ) = Triple(
        EnqueuePrintJobUseCase(
            jobRepo,
            printerRepo,
            DuplicateSubmissionGuard(),
            dispatcher,
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(
                    br.com.atos.printbridge.domain.model.LicenseState(
                        rawToken = TestLicenseTokenSigner.sign(status = licenseStatus, validUntil = licenseValidUntil),
                    ),
                ),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            FakeSettingsRepository(),
        ),
        jobRepo,
        dispatcher,
    )

    @Test
    fun `valid job is enqueued as PENDING using default printer`() = runTest {
        val (useCase, jobRepo, dispatcher) = buildUseCase()

        val job = useCase(
            printerId = null,
            type = PrintPayloadType.ZPL,
            payload = "^XA^FDTeste^FS^XZ",
            documentName = "etiqueta-1",
        )

        assertEquals(PrintJobStatus.PENDING, job.status)
        assertEquals(defaultPrinter.id, job.printerId)
        assertEquals(1, jobRepo.snapshot.size)
        assertEquals(1, dispatcher.requestCount)
    }

    @Test
    fun `job gets max attempts from settings when caller does not override`() = runTest {
        val (useCase, _, _) = buildUseCase()

        val job = useCase(null, PrintPayloadType.ZPL, "^XA^FDTeste^FS^XZ", "etiqueta-retry")

        assertEquals(AppSettings().maxAttempts, job.maxAttempts)
        assertEquals(5, job.maxAttempts)
    }

    @Test
    fun `blank payload is rejected before reaching the repository`() = runTest {
        val (useCase, jobRepo, _) = buildUseCase()

        assertThrows(EmptyPayloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "   ", "doc")
            }
        }
        assertEquals(0, jobRepo.snapshot.size)
    }

    @Test
    fun `payload above limit is rejected`() = runTest {
        val (useCase, _, _) = buildUseCase()
        val oversized = "A".repeat(PrintJob.MAX_PAYLOAD_SIZE_BYTES + 1)

        assertThrows(PayloadTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, oversized, "doc")
            }
        }
    }

    @Test
    fun `copies above the maximum is rejected`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(defaultPrinter) }
        val (useCase, jobRepo, _) = buildUseCase(printerRepo = printerRepo)

        assertThrows(br.com.atos.printbridge.domain.error.InvalidCopiesException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc", copies = PrintJob.MAX_COPIES + 1)
            }
        }
        assertEquals(0, jobRepo.snapshot.size)
    }

    @Test
    fun `copies at the maximum is accepted`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(defaultPrinter) }
        val (useCase, _, _) = buildUseCase(printerRepo = printerRepo)

        val job = useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc", copies = PrintJob.MAX_COPIES)

        assertEquals(PrintJob.MAX_COPIES, job.copies)
    }

    @Test
    fun `no default printer and none specified throws`() = runTest {
        val (useCase, _, _) = buildUseCase(printerRepo = FakePrinterRepository())

        assertThrows(NoDefaultPrinterException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")
            }
        }
    }

    // ---------- Portão de licença (CARREFOUR-PRINT-SERVER-LICENSING.md §10) ----------

    @Test
    fun `new job is rejected when license grace period has expired`() = runTest {
        val (useCase, jobRepo, dispatcher) = buildUseCase(licenseStatus = LicenseTokenStatus.ACTIVE, licenseValidUntil = 1L)

        val ex = assertThrows(LicenseRestrictedException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")
            }
        }

        assertEquals("RESTRICTED", ex.effectiveStatus)
        assertEquals(0, jobRepo.snapshot.size)
        assertEquals(0, dispatcher.requestCount)
    }

    @Test
    fun `new job is rejected when license is suspended even within what would be the offline window`() = runTest {
        val (useCase, jobRepo, _) = buildUseCase(licenseStatus = LicenseTokenStatus.SUSPENDED)

        assertThrows(LicenseRestrictedException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")
            }
        }
        assertEquals(0, jobRepo.snapshot.size)
    }

    @Test
    fun `new job is rejected when device was never activated`() = runTest {
        val jobRepo = FakePrintJobRepository()
        val useCase = EnqueuePrintJobUseCase(
            jobRepo,
            FakePrinterRepository().apply { seed(defaultPrinter) },
            DuplicateSubmissionGuard(),
            FakeQueueDispatcher(),
            EvaluateLicenseStatusUseCase(FakeLicenseRepository(), TestLicenseTokenSigner.createVerifier(), TestLicenseTokenSigner.deviceIdentity()),
            FakeSettingsRepository(),
        )

        val ex = assertThrows(LicenseRestrictedException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")
            }
        }

        assertEquals("NOT_ACTIVATED", ex.effectiveStatus)
        assertEquals(0, jobRepo.snapshot.size)
    }

    @Test
    fun `new job is accepted while still within the offline grace window`() = runTest {
        val futureValidUntil = System.currentTimeMillis() + 72L * 60 * 60 * 1000
        val (useCase, jobRepo, _) = buildUseCase(licenseValidUntil = futureValidUntil)

        val job = useCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")

        assertEquals(PrintJobStatus.PENDING, job.status)
        assertEquals(1, jobRepo.snapshot.size)
    }

    @Test
    fun `a job accepted while OPERATING is never destroyed and can still be retried after the license becomes RESTRICTED`() = runTest {
        // Regra central de CARREFOUR-PRINT-SERVER-LICENSING.md §10: o portão de licença só existe
        // em EnqueuePrintJobUseCase/ReprintJobUseCase (criação). RetryPrintJobUseCase nunca recebeu
        // (nem deve receber) uma dependência de licença — este teste é uma trava de regressão: se
        // algum dia alguém adicionar esse acoplamento por engano, este teste passa a falhar.
        val jobRepo = FakePrintJobRepository()
        val futureValidUntil = System.currentTimeMillis() + 1000
        val (enqueueUseCase, _, _) = buildUseCase(jobRepo = jobRepo, licenseValidUntil = futureValidUntil)
        val job = enqueueUseCase(null, PrintPayloadType.ZPL, "^XA^XZ", "doc")
        jobRepo.update(job.copy(status = PrintJobStatus.FAILED, attempts = 1))

        // Licença expira (grace period terminou) — o job criado ANTES continua no repositório.
        assertEquals(1, jobRepo.snapshot.size)
        assertEquals(PrintJobStatus.FAILED, jobRepo.snapshot.single().status)

        val retryUseCase = RetryPrintJobUseCase(jobRepo, FakeQueueDispatcher())
        retryUseCase(job.id) // nunca lança, mesmo com a licença (hipoteticamente) RESTRICTED agora

        assertEquals(1, jobRepo.snapshot.size) // nunca destruído
        assertEquals(PrintJobStatus.PENDING, jobRepo.snapshot.single().status) // retomado normalmente
    }
}
