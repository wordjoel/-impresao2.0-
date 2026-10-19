package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakePrinterTransport
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private fun testPrinter(id: String = "printer-1") = Printer(
    id = id,
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

private fun testJob(printerId: String, maxAttempts: Int = 3) = PrintJob(
    id = UUID.randomUUID().toString(),
    printerId = printerId,
    type = PrintPayloadType.ZPL,
    payload = "^XA^FDTeste^FS^XZ",
    documentName = "etiqueta-teste",
    copies = 1,
    status = PrintJobStatus.PENDING,
    attempts = 0,
    maxAttempts = maxAttempts,
    errorMessage = null,
    externalDocumentId = null,
    originalJobId = null,
    createdAt = System.currentTimeMillis(),
    startedAt = null,
    sentAt = null,
    updatedAt = System.currentTimeMillis(),
)

class ProcessPrintQueueUseCaseTest {

    @Test
    fun `successful send marks job as SENT`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(testPrinter()) }
        val jobRepo = FakePrintJobRepository()
        val job = testJob("printer-1")
        jobRepo.insert(job)

        val useCase = ProcessPrintQueueUseCase(
            printJobRepository = jobRepo,
            printerRepository = printerRepo,
            settingsRepository = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0)),
            printEngine = PrintEngine(FakePrinterTransport.alwaysSucceeds()),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
        )

        useCase.processPrinter("printer-1")

        val updated = jobRepo.getById(job.id)!!
        assertEquals(PrintJobStatus.SENT, updated.status)
        assertEquals(1, updated.attempts)
    }

    @Test
    fun `failure below max attempts returns job to PENDING for retry`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(testPrinter()) }
        val jobRepo = FakePrintJobRepository()
        val job = testJob("printer-1", maxAttempts = 3)
        jobRepo.insert(job)

        val useCase = ProcessPrintQueueUseCase(
            printJobRepository = jobRepo,
            printerRepository = printerRepo,
            settingsRepository = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0)),
            printEngine = PrintEngine(FakePrinterTransport.alwaysFails(TransportFailureReason.CONNECTION_REFUSED)),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
        )

        // O loop interno de processPrinter já esgota as tentativas sozinho (PENDING -> PROCESSING
        // -> falha -> volta a PENDING -> tenta de novo) até atingir maxAttempts, tudo em uma chamada.
        useCase.processPrinter("printer-1")

        val updated = jobRepo.getById(job.id)!!
        assertEquals(PrintJobStatus.FAILED, updated.status)
        assertEquals(3, updated.attempts)
        assertEquals(TransportFailureReason.CONNECTION_REFUSED.operatorMessage, updated.errorMessage)
    }

    @Test
    fun `offline printer waits 2 4 8 16s between 5 attempts and then marks FAILED`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(testPrinter()) }
        val jobRepo = FakePrintJobRepository()
        val job = testJob("printer-1", maxAttempts = 5)
        jobRepo.insert(job)
        val transport = FakePrinterTransport.alwaysFails(TransportFailureReason.TIMEOUT)

        val useCase = ProcessPrintQueueUseCase(
            printJobRepository = jobRepo,
            printerRepository = printerRepo,
            settingsRepository = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 2_000)),
            printEngine = PrintEngine(transport),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
        )

        val start = testScheduler.currentTime
        useCase.processPrinter("printer-1")

        val updated = jobRepo.getById(job.id)!!
        assertEquals(PrintJobStatus.FAILED, updated.status)
        assertEquals(5, updated.attempts)
        assertEquals(5, transport.sentPayloads.size)
        // Sem espera depois da 5ª falha: o job já é FAILED, não há próxima tentativa a aguardar.
        assertEquals(2_000L + 4_000 + 8_000 + 16_000, testScheduler.currentTime - start)
    }

    @Test
    fun `job that fails once then succeeds ends up SENT`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(testPrinter()) }
        val jobRepo = FakePrintJobRepository()
        val job = testJob("printer-1", maxAttempts = 3)
        jobRepo.insert(job)

        val useCase = ProcessPrintQueueUseCase(
            printJobRepository = jobRepo,
            printerRepository = printerRepo,
            settingsRepository = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0)),
            printEngine = PrintEngine(FakePrinterTransport.succeedsOnAttempt(2)),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
        )

        // A própria fila esgota o retry sozinha dentro de uma chamada (backoff = 0 no teste).
        useCase.processPrinter("printer-1")

        val updated = jobRepo.getById(job.id)!!
        assertEquals(PrintJobStatus.SENT, updated.status)
        assertEquals(2, updated.attempts)
    }

    @Test
    fun `no network keeps job PENDING without spending an attempt`() = runTest {
        val printerRepo = FakePrinterRepository().apply { seed(testPrinter()) }
        val jobRepo = FakePrintJobRepository()
        val job = testJob("printer-1")
        jobRepo.insert(job)

        val useCase = ProcessPrintQueueUseCase(
            printJobRepository = jobRepo,
            printerRepository = printerRepo,
            settingsRepository = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0, autoRetryTransientFailures = false)),
            printEngine = PrintEngine(FakePrinterTransport.alwaysSucceeds()),
            networkStateProvider = FakeNetworkStateProvider(available = false),
            mutexProvider = PrinterQueueMutexProvider(),
        )

        useCase.processPrinter("printer-1")

        val updated = jobRepo.getById(job.id)!!
        assertTrue(updated.status == PrintJobStatus.PENDING || updated.status == PrintJobStatus.FAILED)
        assertEquals(TransportFailureReason.NO_NETWORK.operatorMessage, updated.errorMessage)
    }
}
