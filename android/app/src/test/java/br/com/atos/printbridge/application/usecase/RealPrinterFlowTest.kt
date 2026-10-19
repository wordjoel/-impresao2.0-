package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.application.bridge.SubmitPrintRequestUseCase
import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.ipp.IppDocumentPlan
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterCapabilities
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.model.PwgMediaSize
import br.com.atos.printbridge.domain.printing.ConnectionTestResult
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import br.com.atos.printbridge.domain.printing.TestPageContent
import br.com.atos.printbridge.domain.printing.TestPageRenderer
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.PwgRasterType
import br.com.atos.printbridge.domain.raster.RasterSource
import br.com.atos.printbridge.domain.raster.RasterTarget
import br.com.atos.printbridge.printing.ipp.IppPrinterTransport
import br.com.atos.printbridge.testutil.FakeIppGateway
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakePrinterTransport
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fluxo de impressora real: plano IPP, teste de impressão, status honesto, transporte e roteamento. */
class RealPrinterFlowTest {

    private val pwgOnly = FakeIppGateway.description().capabilities
    private val pdfToo = pwgOnly.copy(documentFormats = pwgOnly.documentFormats + PrinterCapabilities.PDF)

    private fun ippPrinter(caps: PrinterCapabilities? = pwgOnly, isDefault: Boolean = true) = Printer(
        id = "hp", storeId = null, name = "Laser Sala", model = PrinterModel.UNKNOWN, ipAddress = "192.168.15.60", port = 631,
        protocol = PrinterProtocol.IPP, language = PrinterLanguage.AUTO, isDefault = isDefault, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0, manufacturer = "Samsung", modelName = "M2070 Series",
        ippPath = "ipp/print", capabilities = caps,
    )

    private fun rawPrinter(language: PrinterLanguage) = Printer(
        id = "tsc", storeId = null, name = "Etiquetas", model = PrinterModel.UNKNOWN, ipAddress = "192.168.15.70", port = 9100,
        protocol = PrinterProtocol.TCP, language = language, isDefault = false, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )

    private class Fixture(vararg printers: Printer) {
        val printerRepo = FakePrinterRepository()
        val jobs = FakePrintJobRepository()
        val enqueue = EnqueuePrintJobUseCase(
            jobs, printerRepo, DuplicateSubmissionGuard(), FakeQueueDispatcher(),
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            FakeSettingsRepository(AppSettings(retryBaseIntervalMillis = 0)),
        )

        init {
            runBlocking { printers.forEach { printerRepo.upsert(it) } }
        }
    }

    /** Renderizador fake: PDF com cabeçalho real "%PDF" e raster branco com uma linha preta. */
    private class FakeRenderer : TestPageRenderer {
        var lastContent: TestPageContent? = null
        var lastDpi: Int? = null
        override fun renderPdf(content: TestPageContent, media: PwgMediaSize): ByteArray {
            lastContent = content
            return "%PDF-1.4 teste".toByteArray()
        }

        override fun renderRaster(content: TestPageContent, media: PwgMediaSize, dpi: Int): RasterSource {
            lastContent = content
            lastDpi = dpi
            return object : RasterSource {
                override val width = 16
                override val height = 4
                override fun readRow(y: Int, out: IntArray) { for (x in 0 until 16) out[x] = if (y == 1) 0 else 255 }
            }
        }
    }

    // ---------- plano de documento IPP ----------

    @Test
    fun `document plan follows what the printer declared`() {
        val raster = IppDocumentPlan.choose(pwgOnly) as IppDocumentPlan.PwgRaster
        assertEquals(300, raster.dpi) // declara 300 e 600: fica no teto de 300
        assertEquals(PwgRasterType.BLACK_1, raster.type)
        assertEquals("iso_a4_210x297mm", raster.media.name)
        assertTrue(IppDocumentPlan.choose(pdfToo) is IppDocumentPlan.Pdf)
        assertEquals(600, IppDocumentPlan.chooseDpi(listOf(600, 1200)))

        val urfOnly = runCatching { IppDocumentPlan.choose(pwgOnly.copy(documentFormats = listOf("image/urf"))) }.exceptionOrNull()
        assertTrue(urfOnly!!.message!!.contains("image/urf"))
        assertTrue(runCatching { IppDocumentPlan.choose(null) }.exceptionOrNull() is IppDocumentPlan.Unsupported)
        assertTrue(runCatching { IppDocumentPlan.choose(pdfToo.copy(media = listOf("custom_x"), defaultMedia = "custom_x")) }.exceptionOrNull() is IppDocumentPlan.Unsupported)
    }

    @Test
    fun `pwg media names carry their own size`() {
        assertEquals(215.9, PwgMediaSize.parse("na_letter_8.5x11in")!!.widthMm, 0.01)
        assertEquals("A4 (210x297 mm)", PwgMediaSize.parse("iso_a4_210x297mm")!!.label)
        assertEquals(null, PwgMediaSize.parse("roll_max_36x100in-extra"))
    }

    // ---------- imprimir teste ----------

    @Test
    fun `test print for a pwg only ipp printer is a pwg raster job with real printer data`() = runTest {
        val f = Fixture(ippPrinter())
        val renderer = FakeRenderer()

        val job = PrintTestPageUseCase(f.printerRepo, f.enqueue, renderer)("hp")

        assertEquals(PrintPayloadType.PWG_RASTER, job.type)
        assertTrue(job.payload.startsWith("RaS2"))
        assertEquals(300, renderer.lastDpi)
        val lines = renderer.lastContent!!.lines.joinToString("\n")
        assertTrue(lines.contains("Fabricante: Samsung"))
        assertTrue(lines.contains("192.168.15.60:631/ipp/print"))
        // Retrato para o Histórico.
        assertEquals("192.168.15.60:631", job.printerAddress)
        assertEquals("IPP", job.printerProtocol)
        assertEquals("Laser Sala", job.printerName)
    }

    @Test
    fun `test print sends pdf when the printer interprets pdf`() = runTest {
        val f = Fixture(ippPrinter(pdfToo))
        val job = PrintTestPageUseCase(f.printerRepo, f.enqueue, FakeRenderer())("hp")
        assertEquals(PrintPayloadType.PDF, job.type)
        assertTrue(job.payload.startsWith("%PDF"))
    }

    @Test
    fun `raw label printers keep their languages - AUTO as ZPL like always, TSPL as TSPL`() = runTest {
        val f = Fixture(rawPrinter(PrinterLanguage.AUTO), rawPrinter(PrinterLanguage.TSPL).copy(id = "tspl"))
        val useCase = PrintTestPageUseCase(f.printerRepo, f.enqueue, FakeRenderer())

        val auto = useCase("tsc")
        assertEquals(PrintPayloadType.ZPL, auto.type)
        assertTrue(auto.payload.startsWith("^XA"))
        assertEquals("RAW (socket TCP)", auto.printerProtocol)

        val tspl = useCase("tspl")
        assertEquals(PrintPayloadType.TSPL, tspl.type)
        assertTrue(tspl.payload.startsWith("SIZE 100 mm,72 mm"))
    }

    // ---------- testar conexão ----------

    private class FakeTester(val reachable: Boolean) : PrinterConnectionTester {
        override suspend fun test(printer: Printer) = ConnectionTestResult(reachable, if (reachable) "Impressora acessível" else "Conexão recusada", 3)
        override suspend fun testAddress(ipAddress: String, port: Int, timeoutMillis: Int) = ConnectionTestResult(reachable, "", 3)
    }

    private fun status(ipp: FakeIppGateway, tester: PrinterConnectionTester, repo: FakePrinterRepository) =
        CheckPrinterStatusUseCase(ipp, tester, repo, FakeSettingsRepository(), FakeNetworkStateProvider(true))

    @Test
    fun `ipp status comes from the printer and refreshes what it declared`() = runTest {
        val f = Fixture(ippPrinter(caps = null))
        val ipp = FakeIppGateway().apply { printers["ipp://192.168.15.60:631/ipp/print"] = FakeIppGateway.description() }

        val report = status(ipp, FakeTester(true), f.printerRepo)(ippPrinter(caps = null))

        assertTrue(report.online)
        assertEquals("Conectada · Ociosa", report.status)
        assertEquals(pwgOnly, f.printerRepo.getById("hp")?.capabilities)
    }

    @Test
    fun `offline and wrong service are reported with different honest messages`() = runTest {
        val f = Fixture(ippPrinter())
        val down = FakeIppGateway().apply { defaultFailure = IppResult.Failure(IppFailureKind.UNREACHABLE, "failed to connect to /192.168.15.60 (port 631)") }
        val notIpp = FakeIppGateway().apply { defaultFailure = IppResult.Failure(IppFailureKind.NOT_IPP, "HTTP 404") }

        val offline = status(down, FakeTester(false), f.printerRepo)(ippPrinter())
        assertFalse(offline.online)
        assertEquals("Impressora offline", offline.status)
        assertTrue(offline.detail!!.contains("port 631"))

        val wrong = status(notIpp, FakeTester(true), f.printerRepo)(ippPrinter())
        assertEquals("Impressora encontrada, mas serviço de impressão não respondeu", wrong.status)

        val raw = status(down, FakeTester(true), f.printerRepo)(rawPrinter(PrinterLanguage.ZPL))
        assertTrue(raw.online)
        assertEquals("Porta RAW acessível", raw.status)
    }

    // ---------- transporte e roteamento ----------

    @Test
    fun `ipp transport sends the document with its mime and copies as an attribute`() = runTest {
        val f = Fixture(ippPrinter())
        val job = PrintTestPageUseCase(f.printerRepo, f.enqueue, FakeRenderer())("hp").copy(copies = 3)
        val ipp = FakeIppGateway()

        val result = IppPrinterTransport(ipp, FakeSettingsRepository(), FakeNetworkStateProvider(true)).send(ippPrinter(), job)

        assertTrue(result is TransportResult.Success)
        val (endpoint, format, document) = ipp.printJobs.single()
        assertEquals("ipp://192.168.15.60:631/ipp/print", endpoint.uri)
        assertEquals("image/pwg-raster", format)
        // Bytes binários intactos (ISO-8859-1 ida e volta) e sem repetir o documento por cópia.
        assertEquals(job.payload.toByteArray(Charsets.ISO_8859_1).size, document.size)
    }

    @Test
    fun `printer rejection becomes a failure with the ipp status`() = runTest {
        val f = Fixture(ippPrinter())
        val job = PrintTestPageUseCase(f.printerRepo, f.enqueue, FakeRenderer())("hp")
        val ipp = FakeIppGateway().apply { printResult = IppResult.Failure(IppFailureKind.IPP_ERROR, "Print-Job recusado: client-error-document-format-not-supported") }

        val result = IppPrinterTransport(ipp, FakeSettingsRepository(), FakeNetworkStateProvider(true)).send(ippPrinter(), job) as TransportResult.Failure

        assertEquals(TransportFailureReason.PRINTER_REJECTED, result.reason)
        assertTrue(result.technicalMessage.contains("document-format-not-supported"))
    }

    @Test
    fun `engine never sends a pdf into a raw socket nor zpl into ipp`() = runTest {
        val f = Fixture(ippPrinter())
        val pwgJob = PrintTestPageUseCase(f.printerRepo, f.enqueue, FakeRenderer())("hp")
        val socket = FakePrinterTransport.alwaysSucceeds()
        val ipp = FakeIppGateway()
        val engine = PrintEngine(socket, IppPrinterTransport(ipp, FakeSettingsRepository(), FakeNetworkStateProvider(true)))

        val intoRaw = engine.send(rawPrinter(PrinterLanguage.ZPL), pwgJob) as TransportResult.Failure
        assertEquals(TransportFailureReason.PROTOCOL_MISMATCH, intoRaw.reason)
        assertTrue(socket.sentPayloads.isEmpty())

        val zplIntoIpp = engine.send(ippPrinter(), pwgJob.copy(type = PrintPayloadType.ZPL, payload = "^XA^XZ")) as TransportResult.Failure
        assertEquals(TransportFailureReason.PROTOCOL_MISMATCH, zplIntoIpp.reason)
        assertTrue(ipp.printJobs.isEmpty())

        assertTrue(engine.send(ippPrinter(), pwgJob) is TransportResult.Success)
        assertEquals(1, ipp.printJobs.size)
    }

    // ---------- API local / PrintService para impressora IPP ----------

    private class PagesRasterizer(private val pages: Int) : DocumentRasterizer {
        var lastTarget: RasterTarget? = null
        override suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage> {
            lastTarget = target
            return (0 until pages).map { GrayImage(8, 2, IntArray(16) { 255 }) }
        }
    }

    @Test
    fun `pdf to an ipp printer goes intact when it reads pdf and as one pwg job otherwise`() = runTest {
        val f = Fixture(ippPrinter(pdfToo), ippPrinter(pwgOnly, isDefault = false).copy(id = "pwg"))
        val rasterizer = PagesRasterizer(pages = 3)
        val submit = SubmitPrintRequestUseCase(f.printerRepo, f.enqueue, rasterizer)
        val pdf = "%PDF-1.7 documento".toByteArray()

        val direct = submit(PrintJobRequest(DocumentType.PDF, documentBytes = pdf, printerProfileId = "hp"))
        val directJob = f.jobs.getById(direct.jobIds.single())!!
        assertEquals(PrintPayloadType.PDF, directJob.type)
        assertEquals("%PDF-1.7 documento", directJob.payload)

        val raster = submit(PrintJobRequest(DocumentType.PDF, documentBytes = pdf, printerProfileId = "pwg"))
        val rasterJob = f.jobs.getById(raster.jobIds.single())!!
        assertEquals(PrintPayloadType.PWG_RASTER, rasterJob.type)
        assertEquals(RasterTarget.forLabel(210, 297, 300), rasterizer.lastTarget)
        // 3 páginas num só trabalho IPP: "RaS2" uma vez, 3 cabeçalhos PwgRaster.
        assertEquals(3, Regex("PwgRaster").findAll(rasterJob.payload).count())
    }

    @Test
    fun `label languages are refused for ipp printers`() {
        val f = Fixture(ippPrinter())
        val submit = SubmitPrintRequestUseCase(f.printerRepo, f.enqueue, PagesRasterizer(1))
        val error = runCatching { runBlocking { submit(PrintJobRequest(DocumentType.RAW_ZPL_TSPL, rawPayload = "^XA^XZ", printerProfileId = "hp")) } }.exceptionOrNull()
        assertTrue(error is PrinterCapabilityException)
        assertEquals(0, f.jobs.snapshot.size)
    }
}
