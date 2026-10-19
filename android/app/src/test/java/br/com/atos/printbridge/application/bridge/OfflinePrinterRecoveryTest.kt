package br.com.atos.printbridge.application.bridge

import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.application.usecase.PrinterQueueMutexProvider
import br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.printing.TcpPrinterTransport
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Etapa 5 do prompt mestre, com socket TCP REAL (TcpPrinterTransport, sem fake de transporte):
 * impressora desligada → o job fica persistido e volta para a fila → a "impressora" liga → o job
 * sai sozinho no retry seguinte, byte a byte, sem ninguém reenviar nada.
 */
class OfflinePrinterRecoveryTest {

    private val executor = Executors.newSingleThreadExecutor()
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
        executor.shutdownNow()
    }

    /** Porta livre sem ninguém escutando: conectar nela dá "connection refused", como uma TSC desligada. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** "Liga a impressora": começa a escutar na porta e devolve os bytes que chegarem. */
    private fun powerOnPrinter(port: Int): CompletableFuture<ByteArray> {
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", port)) }
        server = socket
        return CompletableFuture.supplyAsync({ socket.accept().use { it.getInputStream().readBytes() } }, executor)
    }

    @Test
    fun `job survives an offline printer and is delivered automatically when it comes back`() = runTest {
        val port = freePort()
        val printers = FakePrinterRepository()
        printers.upsert(
            Printer(
                id = "p1", storeId = null, name = "TSC", model = PrinterModel.TSC_ML241, ipAddress = "127.0.0.1",
                port = port, protocol = PrinterProtocol.TCP, language = PrinterLanguage.ZPL, isDefault = true,
                enabled = true, lastSeenAt = null, createdAt = 0, updatedAt = 0,
            ),
        )
        val jobs = FakePrintJobRepository()
        val payload = "^XA^FO50,50^A0N,40,40^FDVOLTOU^FS^XZ"
        jobs.insert(
            PrintJob(
                id = "job-1", printerId = "p1", type = PrintPayloadType.ZPL, payload = payload, documentName = "etiqueta",
                copies = 1, status = PrintJobStatus.PENDING, attempts = 0, maxAttempts = 5, errorMessage = null,
                externalDocumentId = null, originalJobId = null, createdAt = 0, startedAt = null, sentAt = null, updatedAt = 0,
            ),
        )
        val settings = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 2_000, connectTimeoutMillis = 1_000, socketTimeoutMillis = 2_000))
        val events = PrintJobEventBus()
        val queue = ProcessPrintQueueUseCase(
            printJobRepository = jobs,
            printerRepository = printers,
            settingsRepository = settings,
            printEngine = PrintEngine(TcpPrinterTransport(settings, FakeNetworkStateProvider(available = true))),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
            eventBus = events,
        )

        val statusWhileOffline = mutableListOf<PrintJobStatus>()
        var received: CompletableFuture<ByteArray>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.flow.collect { event ->
                if (event is PrintJobEvent.Retrying) {
                    statusWhileOffline += runBlocking { jobs.getById("job-1")!!.status }
                    // Impressora volta depois da 2ª falha.
                    if (event.attempt == 2) received = powerOnPrinter(port)
                }
            }
        }

        queue.processPrinter("p1")

        // Offline: persistido e de volta à fila (nunca perdido, nunca FAILED antes da hora).
        assertEquals(listOf(PrintJobStatus.PENDING, PrintJobStatus.PENDING), statusWhileOffline)
        val job = jobs.getById("job-1")!!
        assertEquals(PrintJobStatus.SENT, job.status)
        assertEquals(3, job.attempts)
        assertEquals(payload, String(received!!.get(5, TimeUnit.SECONDS), Charsets.UTF_8))
    }
}
