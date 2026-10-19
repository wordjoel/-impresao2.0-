package br.com.atos.printbridge.application.printservice

import br.com.atos.printbridge.application.bridge.GetJobStatusUseCase
import br.com.atos.printbridge.application.bridge.SubmitPrintRequestUseCase
import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.application.usecase.DuplicateSubmissionGuard
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.PrinterQueueMutexProvider
import br.com.atos.printbridge.application.usecase.ProcessPrintQueueUseCase
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakePrinterTransport
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPrintJobBridgeTest {

    private fun printer(id: String = "p1", isDefault: Boolean = true, enabled: Boolean = true) = Printer(
        id = id, storeId = "loja-1", name = "TSC $id", model = PrinterModel.TSC_ML241, ipAddress = "10.0.0.5",
        port = 9100, protocol = PrinterProtocol.TCP, language = PrinterLanguage.ZPL, isDefault = isDefault, enabled = enabled,
        lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )

    private class FakeRasterizer(private val pages: Int) : DocumentRasterizer {
        var lastTarget: RasterTarget? = null
        var lastKind: DocumentKind? = null
        override suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage> {
            lastTarget = target
            lastKind = kind
            return (0 until pages).map { i ->
                GrayImage(target.widthDots, target.heightDots, IntArray(target.widthDots * target.heightDots) { if (it % target.widthDots == i) 0 else 255 })
            }
        }
    }

    private class Fixture(
        vararg seeded: Printer,
        rasterPages: Int = 1,
        licenseValidUntil: Long = Long.MAX_VALUE / 2,
        settings: AppSettings = AppSettings(retryBaseIntervalMillis = 0),
    ) {
        val printers = FakePrinterRepository()
        val jobs = FakePrintJobRepository()
        val settingsRepo = FakeSettingsRepository(settings)
        val rasterizer = FakeRasterizer(rasterPages)
        val bridge = SystemPrintJobBridge(
            printers,
            jobs,
            SubmitPrintRequestUseCase(
                printers,
                EnqueuePrintJobUseCase(
                    jobs, printers, DuplicateSubmissionGuard(), FakeQueueDispatcher(),
                    EvaluateLicenseStatusUseCase(
                        FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = licenseValidUntil))),
                        TestLicenseTokenSigner.createVerifier(),
                        TestLicenseTokenSigner.deviceIdentity(),
                    ),
                    settingsRepo,
                ),
                rasterizer,
            ),
            GetJobStatusUseCase(jobs),
        )

        init {
            runBlocking { seeded.forEach { printers.upsert(it) } }
        }

        fun queue(transport: PrinterTransport) = ProcessPrintQueueUseCase(
            printJobRepository = jobs,
            printerRepository = printers,
            settingsRepository = settingsRepo,
            printEngine = PrintEngine(transport),
            networkStateProvider = FakeNetworkStateProvider(available = true),
            mutexProvider = PrinterQueueMutexProvider(),
            eventBus = PrintJobEventBus(),
        )
    }

    // ---------- Como a impressora aparece no diálogo ----------

    @Test
    fun `media offered to the system is the label size of the profile in mils`() {
        val spec = SystemPrinterSpecs.of(printer().copy(labelWidthMm = 100, labelHeightMm = 72, dpi = 300))!!

        val media = spec.medias.single()
        assertEquals(3937, media.widthMils)
        assertEquals(2835, media.heightMils)
        assertEquals(listOf(300), spec.resolutionsDpi)
        assertEquals("PB_100X72MM", media.id)
        assertEquals("Etiqueta 100x72 mm", media.label)
        assertEquals("ZPL · 100x72 mm · 300 dpi · 10.0.0.5:9100", spec.description)
        assertFalse(spec.color)
    }

    @Test
    fun `AUTO language is shown as ZPL like the rest of the app`() {
        assertTrue(SystemPrinterSpecs.of(printer().copy(language = PrinterLanguage.AUTO))!!.description.startsWith("ZPL "))
    }

    @Test
    fun `ipp printer offers the media and resolutions it declared`() {
        val caps = br.com.atos.printbridge.testutil.FakeIppGateway.description().capabilities
        val ipp = printer().copy(
            protocol = br.com.atos.printbridge.domain.model.PrinterProtocol.IPP, port = 631, ippPath = "ipp/print",
            manufacturer = "Samsung", modelName = "M2070 Series", capabilities = caps,
        )
        val spec = SystemPrinterSpecs.of(ipp)!!

        assertEquals(listOf("iso_a4_210x297mm", "na_letter_8.5x11in"), spec.medias.map { it.id })
        assertEquals("iso_a4_210x297mm", spec.defaultMediaId)
        assertEquals(8268, spec.medias.first().widthMils) // 210 mm
        assertEquals(listOf(600), spec.resolutionsDpi)
        assertEquals("IPP · Samsung M2070 Series · 10.0.0.5:631", spec.description)
        assertFalse("raster gerado é monocromático", spec.color)
        // Sem capacidades declaradas não há como montar o diálogo: fica fora da lista.
        assertEquals(null, SystemPrinterSpecs.of(ipp.copy(capabilities = null)))
    }

    @Test
    fun `only enabled printers are listed with the default first`() = runTest {
        val f = Fixture(printer("a", isDefault = false), printer("b", isDefault = true), printer("off", isDefault = false, enabled = false))

        assertEquals(listOf("b", "a"), f.bridge.printers().map { it.localId })
        assertEquals(null, f.bridge.printer("off"))
        assertEquals("a", f.bridge.printer("a")?.localId)
    }

    // ---------- Job do sistema → fila da Fase 1 ----------

    @Test
    fun `system pdf enters the same queue rasterized for the chosen printer`() = runTest {
        val f = Fixture(printer("p1"), printer("p2", isDefault = false).copy(labelWidthMm = 60, labelHeightMm = 40), rasterPages = 2)

        val submitted = f.bridge.submit("p2", byteArrayOf(1, 2, 3), copies = 0, documentName = "Nota.pdf")

        assertEquals("p2", submitted.printerId)
        assertEquals(DocumentKind.PDF, f.rasterizer.lastKind)
        assertEquals(RasterTarget.forLabel(60, 40, 203), f.rasterizer.lastTarget)
        val pages = f.jobs.getByGroupId(submitted.id)
        assertEquals(2, pages.size)
        // O framework pode mandar copies=0; a fila nunca recebe menos de 1.
        assertTrue(pages.all { it.status == PrintJobStatus.PENDING && it.copies == 1 && it.printerId == "p2" })
    }

    @Test
    fun `unknown printer and expired license are refused before anything is queued`() {
        val f = Fixture(printer("p1"))
        assertThrows(PrinterNotFoundException::class.java) { runBlocking { f.bridge.submit("sumiu", byteArrayOf(1), 1, null) } }

        val expired = Fixture(printer("p1"), licenseValidUntil = 1)
        assertThrows(LicenseRestrictedException::class.java) { runBlocking { expired.bridge.submit("p1", byteArrayOf(1), 1, null) } }

        assertEquals(0, f.jobs.snapshot.size + expired.jobs.snapshot.size)
    }

    @Test
    fun `system job completes only after the queue delivers every page`() = runTest {
        val f = Fixture(printer("p1"), rasterPages = 3)
        val submitted = f.bridge.submit("p1", byteArrayOf(1), 1, "Relatório")

        val outcome = async { f.bridge.awaitOutcome(submitted, pollMillis = 100) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse("não pode concluir antes de a fila entregar", outcome.isCompleted)

        f.queue(FakePrinterTransport.alwaysSucceeds()).processPrinter("p1")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(SystemJobOutcome.Completed, outcome.await())
    }

    @Test
    fun `printer that never answers marks the job as waiting once and then failed`() = runTest {
        val f = Fixture(printer("p1"), settings = AppSettings(retryBaseIntervalMillis = 0, maxAttempts = 1))
        val submitted = f.bridge.submit("p1", byteArrayOf(1), 1, null)
        val notices = mutableListOf<String>()

        val outcome = async { f.bridge.awaitOutcome(submitted, pollMillis = 100, waitingNoticeAfterMillis = 500) { notices += it } }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(SystemPrintJobBridge.WAITING_MESSAGE), notices)

        f.queue(FakePrinterTransport.alwaysFails()).processPrinter("p1")
        advanceTimeBy(200)
        runCurrent()

        val result = outcome.await()
        assertTrue(result is SystemJobOutcome.Failed)
        assertEquals(1, notices.size)
    }

    @Test
    fun `cancelling from the system dialog removes pages still waiting in the queue`() = runTest {
        val f = Fixture(printer("p1"), rasterPages = 2)
        val submitted = f.bridge.submit("p1", byteArrayOf(1), 1, null)

        f.bridge.cancel(submitted)

        assertTrue(f.jobs.getByGroupId(submitted.id).all { it.status == PrintJobStatus.CANCELLED })
        assertEquals(SystemJobOutcome.Cancelled, f.bridge.awaitOutcome(submitted))
    }
}
