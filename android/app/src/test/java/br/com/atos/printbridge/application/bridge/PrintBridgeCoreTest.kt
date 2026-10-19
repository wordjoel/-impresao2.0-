package br.com.atos.printbridge.application.bridge

import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.JobStatus
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.application.usecase.DuplicateSubmissionGuard
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.PrinterQueueMutexProvider
import br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase
import br.com.atos.printbridge.application.usecase.SavePrinterUseCase
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import br.com.atos.printbridge.domain.template.BuiltInPrintTemplates
import br.com.atos.printbridge.domain.template.PlaceholderTemplateRenderer
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakePrinterTransport
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintBridgeCoreTest {

    private val sifData = mapOf(
        "loteSequencia" to "96644", "pesoLiquido" to "10,020 Kg", "pesoBruto" to "10,390 Kg",
        "codigoBarra1" to "789", "codigoBarra2" to "", "codigoBarra3" to "", "codigoBarra4" to "",
        "qrCodePayload" to "lote-96644",
    )

    private fun printer(language: PrinterLanguage = PrinterLanguage.ZPL, id: String = "p1") = Printer(
        id = id, storeId = "loja-1", name = "TSC Expedição", model = PrinterModel.TSC_ML241, ipAddress = "10.0.0.5",
        port = 9100, protocol = PrinterProtocol.TCP, language = language, isDefault = true, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )

    /** Rasterizador fake: N páginas do tamanho da etiqueta; página i tem só a coluna i preta (payloads distintos). */
    private class FakeRasterizer(private val pages: Int) : DocumentRasterizer {
        var lastTarget: RasterTarget? = null
        override suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage> {
            lastTarget = target
            return (0 until pages).map { i ->
                GrayImage(target.widthDots, target.heightDots, IntArray(target.widthDots * target.heightDots) { if (it % target.widthDots == i) 0 else 255 })
            }
        }
    }

    private inner class Fixture(printer: Printer = printer(), rasterPages: Int = 1, settings: AppSettings = AppSettings(retryBaseIntervalMillis = 0)) {
        val printers = FakePrinterRepository()
        val jobs = FakePrintJobRepository()
        val dispatcher = FakeQueueDispatcher()
        val settingsRepo = FakeSettingsRepository(settings)
        val rasterizer = FakeRasterizer(rasterPages)
        val events = PrintJobEventBus()
        val enqueue = EnqueuePrintJobUseCase(
            jobs, printers, DuplicateSubmissionGuard(), dispatcher,
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            settingsRepo,
        )
        val submit = SubmitPrintRequestUseCase(printers, enqueue, rasterizer)
        val status = GetJobStatusUseCase(jobs)

        init {
            kotlinx.coroutines.runBlocking { printers.upsert(printer) }
        }

        fun queue(transport: PrinterTransport) = ProcessPrintQueueUseCase(
            printJobRepository = jobs,
            printerRepository = printers,
            settingsRepository = settingsRepo,
            printEngine = PrintEngine(transport),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
            eventBus = events,
        )
    }

    // ---------- TEMPLATE ----------

    @Test
    fun `template request is rendered persisted as queued and sent byte exact`() = runTest {
        val f = Fixture()

        val submitted = f.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID, variables = sifData))

        // Persistido ANTES de qualquer envio (Etapa 5): está na fila como PENDING e o worker foi acionado.
        val job = f.jobs.getById(submitted.id)!!
        assertEquals(PrintJobStatus.PENDING, job.status)
        assertEquals(PrintPayloadType.ZPL, job.type)
        assertEquals(JobStatus.Queued(0), f.status(submitted.id))
        assertEquals(1, f.dispatcher.requestCount)

        val transport = FakePrinterTransport.alwaysSucceeds()
        f.queue(transport).processPrinter("p1")

        val expected = PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, sifData)
        assertEquals(expected, String(transport.sentPayloads.single(), Charsets.UTF_8))
        assertTrue(f.status(submitted.id) is JobStatus.Completed)
    }

    @Test
    fun `template with missing variable is refused and nothing reaches the queue`() = runTest {
        val f = Fixture()

        assertThrows(MissingTemplateVariablesException::class.java) {
            kotlinx.coroutines.runBlocking {
                f.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID, variables = sifData - "pesoBruto"))
            }
        }
        assertEquals(0, f.jobs.snapshot.size)
    }

    @Test
    fun `unknown template and ZPL template on TSPL printer are refused`() = runTest {
        val zpl = Fixture()
        assertThrows(UnknownTemplateException::class.java) {
            kotlinx.coroutines.runBlocking { zpl.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = "nao-existe", variables = emptyMap())) }
        }
        val tspl = Fixture(printer(PrinterLanguage.TSPL))
        assertThrows(TemplateLanguageMismatchException::class.java) {
            kotlinx.coroutines.runBlocking {
                tspl.submit(PrintJobRequest(DocumentType.TEMPLATE, templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID, variables = sifData))
            }
        }
    }

    // ---------- RAW ----------

    @Test
    fun `raw payload goes to the queue untouched typed by the printer language`() = runTest {
        val f = Fixture(printer(PrinterLanguage.TSPL))

        val submitted = f.submit(PrintJobRequest(DocumentType.RAW_ZPL_TSPL, rawPayload = "SIZE 100 mm,72 mm\r\nCLS\r\nPRINT 1\r\n"))

        val job = f.jobs.getById(submitted.id)!!
        assertEquals(PrintPayloadType.TSPL, job.type)
        assertEquals("SIZE 100 mm,72 mm\r\nCLS\r\nPRINT 1\r\n", job.payload)
    }

    // ---------- PDF / IMAGE ----------

    @Test
    fun `pdf is rasterized with the printer media profile and each page becomes a grouped job in order`() = runTest {
        val f = Fixture(printer().copy(dpi = 300, labelWidthMm = 60, labelHeightMm = 40), rasterPages = 3)

        val submitted = f.submit(PrintJobRequest(DocumentType.PDF, documentBytes = byteArrayOf(1), documentName = "Relatório"))

        assertEquals(RasterTarget.forLabel(60, 40, 300), f.rasterizer.lastTarget)
        assertEquals(3, submitted.pageCount)
        val pages = f.jobs.getByGroupId(submitted.id)
        assertEquals(listOf(0, 1, 2), pages.map { it.pageIndex })
        assertTrue(pages.all { it.groupId == submitted.id && it.pageCount == 3 && it.payload.contains("^GFA") })
        assertEquals(listOf("Relatório (1/3)", "Relatório (2/3)", "Relatório (3/3)"), pages.map { it.documentName })
        assertEquals(JobStatus.Queued(0), f.status(submitted.id))

        val transport = FakePrinterTransport.alwaysSucceeds()
        f.queue(transport).processPrinter("p1")

        // Ordem de impressão preservada: o que saiu no socket é página 1, 2, 3.
        assertEquals(pages.map { it.payload }, transport.sentPayloads.map { String(it, Charsets.UTF_8) })
        assertTrue(f.status(submitted.id) is JobStatus.Completed)
    }

    @Test
    fun `single page image is a plain job not a group`() = runTest {
        val f = Fixture(rasterPages = 1)

        val submitted = f.submit(PrintJobRequest(DocumentType.IMAGE, documentBytes = byteArrayOf(1)))

        assertEquals(submitted.jobIds.single(), submitted.id)
        assertEquals(null, f.jobs.getById(submitted.id)!!.groupId)
    }

    @Test
    fun `document with a failed page reports partial completion and emits page and document events`() = runTest {
        val f = Fixture(rasterPages = 3, settings = AppSettings(retryBaseIntervalMillis = 0, maxAttempts = 2))
        val seen = mutableListOf<PrintJobEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.events.flow.collect { seen += it } }
        val submitted = f.submit(PrintJobRequest(DocumentType.PDF, documentBytes = byteArrayOf(1)))
        val secondPage = f.jobs.getByGroupId(submitted.id)[1].payload
        // A página 2 sempre falha; as outras saem.
        val transport = FakePrinterTransport { _, bytes ->
            if (String(bytes, Charsets.UTF_8) == secondPage) TransportResult.Failure(TransportFailureReason.TIMEOUT, "timeout")
            else TransportResult.Success(bytes.size, 1)
        }

        f.queue(transport).processPrinter("p1")

        assertEquals(JobStatus.PartiallyCompleted(pagesOk = 2, pagesTotal = 3), f.status(submitted.id))
        val pagesPrinted = seen.filterIsInstance<PrintJobEvent.PagePrinted>().map { it.pageNumber }
        assertEquals(listOf(1, 3), pagesPrinted)
        assertEquals(1, seen.filterIsInstance<PrintJobEvent.Retrying>().size)
        assertEquals(1, seen.filterIsInstance<PrintJobEvent.Failed>().size)
        assertEquals(PrintJobEvent.DocumentFinished(seen.last().jobId, submitted.id, 2, 3), seen.last())
    }

    // ---------- Printer Profile Manager via API ----------

    @Test
    fun `printer profile from the api contract round trips to the app printer`() = runTest {
        val printers = FakePrinterRepository()
        val profiles = PrinterProfileUseCases(printers, SavePrinterUseCase(printers))

        val saved = profiles.register(
            PrinterProfile(id = null, storeId = "loja-387", name = "Balança 3", ipAddress = "10.1.2.3", protocol = "tspl", dpi = 300, defaultPaperSize = "60x40", isDefault = true),
        )

        assertEquals(PrinterLanguage.TSPL, saved.language)
        assertEquals(Triple(300, 60, 40), Triple(saved.dpi, saved.labelWidthMm, saved.labelHeightMm))
        val listed = profiles.list("loja-387").single()
        assertEquals("TSPL", listed.protocol)
        assertEquals("60x40", listed.defaultPaperSize)
        assertEquals(true, listed.isDefault)
        assertEquals(emptyList<PrinterProfile>(), profiles.list("outra-loja"))
    }
}
