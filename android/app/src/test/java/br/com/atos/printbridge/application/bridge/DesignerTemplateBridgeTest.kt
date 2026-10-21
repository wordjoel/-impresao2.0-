package br.com.atos.printbridge.application.bridge

import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.application.coletor.ColetorFixture
import br.com.atos.printbridge.application.coletor.ColetorTemplateResolver
import br.com.atos.printbridge.application.coletor.FakeColetorRemote
import br.com.atos.printbridge.application.coletor.FakeColetorStore
import br.com.atos.printbridge.application.coletor.FakeDeviceClock
import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.application.usecase.DuplicateSubmissionGuard
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.PrinterQueueMutexProvider
import br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase
import br.com.atos.printbridge.domain.coletor.ColetorPackageVerifier
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.error.TemplateVersionUnconfirmedException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import br.com.atos.printbridge.domain.template.BuiltInPrintTemplates
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakePrinterTransport
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passo 6 de ponta a ponta na ponte: printJob(TEMPLATE) com o templateId de um pacote do designer
 * (pacote real assinado pelo print-server) → fila → bytes que chegam à impressora iguais aos do PC.
 */
class DesignerTemplateBridgeTest {

    private val tsc = Printer(
        id = "p1", storeId = null, name = "MB241", model = PrinterModel.TSC_MB241, ipAddress = "10.0.0.5", port = 9100,
        protocol = PrinterProtocol.TCP, language = PrinterLanguage.TSPL, isDefault = true, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0, dpi = 203,
    )

    private object NoRaster : DocumentRasterizer {
        override suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage> = error("não usado")
    }

    private class Fixture(printer: Printer) {
        val printers = FakePrinterRepository()
        val jobs = FakePrintJobRepository()
        val settings = FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0))
        val remote = FakeColetorRemote()
        val enqueue = EnqueuePrintJobUseCase(
            jobs, printers, DuplicateSubmissionGuard(), FakeQueueDispatcher(),
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            settings,
        )
        val submit = SubmitPrintRequestUseCase(printers, enqueue, NoRaster, ColetorTemplateResolver(FakeColetorStore(), remote, FakeDeviceClock()))

        init {
            runBlocking { printers.upsert(printer) }
        }

        fun queue(transport: FakePrinterTransport) = ProcessPrintQueueUseCase(
            printJobRepository = jobs,
            printerRepository = printers,
            settingsRepository = settings,
            printEngine = PrintEngine(transport),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
            eventBus = PrintJobEventBus(),
        )
    }

    @Test
    fun `template do designer chega na impressora com os bytes exatos do PC, copias no comando`() = runTest {
        val f = Fixture(tsc)
        val (values, copies, expected) = ColetorFixture.case(2, 2)
        val submitted = f.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = ColetorFixture.templateId, variables = values, copies = copies))

        val job = f.jobs.getById(submitted.id)!!
        assertEquals(PrintPayloadType.TSPL, job.type)
        // Cópias vão no PRINT 1,n do pacote; a fila manda a etiqueta uma vez só.
        assertEquals(1, job.copies)
        assertEquals("Fixture coletor — Gôndola v7", job.documentName)

        val transport = FakePrinterTransport.alwaysSucceeds()
        f.queue(transport).processPrinter("p1")
        val sent = transport.sentPayloads.single()
        assertEquals(expected, ColetorPackageVerifier.sha256Hex(sent))
        assertTrue(sent.toString(Charsets.ISO_8859_1).endsWith("PRINT 1,2\r\n"))
    }

    @Test
    fun `embutido continua igual e id desconhecido segue UNKNOWN_TEMPLATE`() = runTest {
        val f = Fixture(tsc.copy(language = PrinterLanguage.ZPL))
        f.submit(
            PrintJobRequest(
                DocumentType.TEMPLATE,
                templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID,
                variables = BuiltInPrintTemplates.SIF_PESAGEM_100X72.variables.associateWith { "1" },
            ),
        )
        // O embutido nem consulta o PC.
        assertEquals(0, f.remote.versionCalls)

        f.remote.versionAnswer = VersionCheck.NotPublished
        assertThrows(UnknownTemplateException::class.java) {
            runBlocking { f.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = "nao-existe", variables = emptyMap())) }
        }
    }

    @Test
    fun `sem confirmar a versao nada entra na fila`() = runTest {
        val f = Fixture(tsc)
        f.remote.versionAnswer = VersionCheck.Unauthorized
        assertThrows(TemplateVersionUnconfirmedException::class.java) {
            runBlocking { f.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = ColetorFixture.templateId, variables = ColetorFixture.case(2, 1).first)) }
        }
        assertTrue(f.jobs.observeActive().first().isEmpty())
    }
}
