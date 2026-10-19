package br.com.atos.printbridge.domain.raster

import br.com.atos.printbridge.application.service.PrintEngine
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.validation.ZplValidator
import br.com.atos.printbridge.testutil.FakePrinterTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterPipelineTest {

    private fun gray(width: Int, height: Int, value: (Int, Int) -> Int) =
        GrayImage(width, height, IntArray(width * height) { value(it % width, it / width) })

    // ---------- geometria ----------

    @Test
    fun `100x72 label at 203 dpi is 792x575 dots with width multiple of 8`() {
        val target = RasterTarget.forLabel(widthMm = 100, heightMm = 72, dpi = 203)
        assertEquals(792, target.widthDots)
        assertEquals(575, target.heightDots)
    }

    @Test
    fun `300 dpi profile yields proportionally more dots`() {
        val target = RasterTarget.forLabel(widthMm = 100, heightMm = 72, dpi = 300)
        assertEquals(1176, target.widthDots)
        assertEquals(850, target.heightDots)
    }

    @Test
    fun `A4 portrait page is fitted by height on a landscape label without distortion`() {
        val target = RasterTarget.forLabel(100, 72, 203)
        val (w, h) = target.fit(595, 842) // A4 em pontos PDF
        assertEquals(575, h)
        assertEquals((595 * 575.0 / 842).toInt(), w)
    }

    // ---------- binarização ----------

    @Test
    fun `threshold turns dark pixels black and light pixels white`() {
        val image = gray(4, 1) { x, _ -> listOf(0, 127, 128, 255)[x] }
        val mono = ThresholdBinarizer().binarize(image)
        assertEquals(listOf(true, true, false, false), (0 until 4).map { mono.isBlack(it, 0) })
    }

    @Test
    fun `floyd steinberg keeps pure black and white intact`() {
        val black = FloydSteinbergBinarizer().binarize(gray(16, 16) { _, _ -> 0 })
        val white = FloydSteinbergBinarizer().binarize(gray(16, 16) { _, _ -> 255 })
        assertTrue((0 until 16).all { x -> (0 until 16).all { y -> black.isBlack(x, y) } })
        assertTrue((0 until 16).none { x -> (0 until 16).any { y -> white.isBlack(x, y) } })
    }

    @Test
    fun `floyd steinberg renders mid gray as roughly half the dots where threshold would be all white`() {
        val midGray = gray(64, 64) { _, _ -> 140 }
        val dithered = FloydSteinbergBinarizer().binarize(midGray)
        val thresholded = ThresholdBinarizer().binarize(midGray)

        val blackRatio = (0 until 64).sumOf { y -> (0 until 64).count { x -> dithered.isBlack(x, y) } } / 4096.0
        assertTrue("proporção preta $blackRatio", blackRatio in 0.35..0.55)
        assertTrue((0 until 64).none { y -> (0 until 64).any { x -> thresholded.isBlack(x, y) } })
    }

    // ---------- codificação ----------

    @Test
    fun `ZPL encoder emits GFA with exact byte counts and hex rows`() {
        // 10 x 2: linha 0 = primeiro e último ponto pretos; linha 1 = toda preta.
        val mono = MonoBitmap.fromRows(10, 2) { x, y -> y == 1 || x == 0 || x == 9 }

        val zpl = ZplGraphicEncoder.encode(mono)

        // 2 bytes por linha: 1000 0000 | 0100 0000 e 1111 1111 | 1100 0000 (preenchimento em branco).
        assertEquals("^XA\n^PW10\n^LL2\n^FO0,0^GFA,4,4,2,8040FFC0^FS\n^XZ", zpl)
        assertEquals(emptyList<Any>(), ZplValidator.validate(zpl))
    }

    @Test
    fun `TSPL encoder emits SIZE CLS BITMAP PRINT with inverted binary data`() {
        val mono = MonoBitmap.fromRows(8, 2) { x, y -> y == 0 && x < 4 } // 1111 0000 / 0000 0000

        val bytes = TsplGraphicEncoder.encode(mono, labelWidthMm = 100, labelHeightMm = 72)

        val header = "SIZE 100 mm,72 mm\r\nCLS\r\nBITMAP 0,0,1,2,0,".toByteArray(Charsets.US_ASCII)
        val footer = "\r\nPRINT 1\r\n".toByteArray(Charsets.US_ASCII)
        // Polaridade TSC: 0 queima o ponto, então 0xF0 vira 0x0F e a linha branca vira 0xFF.
        assertArrayEquals(header + byteArrayOf(0x0F, 0xFF.toByte()) + footer, bytes)
    }

    // ---------- ponta a ponta (fila → PrintEngine → socket) ----------

    @Test
    fun `multi page document becomes one payload per page in original order`() {
        val target = RasterTarget.forLabel(10, 10, 203)
        val pages = listOf(0, 255, 0).map { v -> gray(target.widthDots, target.heightDots) { _, _ -> v } }

        val payloads = RasterPageEncoder.encode(pages, PrinterLanguage.ZPL, target, DitherMode.THRESHOLD)

        assertEquals(3, payloads.size)
        assertTrue(payloads[0].payload.contains("FFFF"))
        assertTrue(payloads[1].payload.contains("0000") && !payloads[1].payload.contains("FFFF"))
        assertEquals(payloads[0], payloads[2])
    }

    @Test
    fun `TSPL raster bytes survive the queue string and reach the socket unchanged`() = runTest {
        val target = RasterTarget.forLabel(20, 10, 203)
        // Gradiente horizontal: gera todos os padrões de byte possíveis na linha, inclusive >0x7F.
        val page = gray(target.widthDots, target.heightDots) { x, _ -> x * 255 / target.widthDots }
        val payload = RasterPageEncoder.encode(listOf(page), PrinterLanguage.TSPL, target, DitherMode.FLOYD_STEINBERG).single()
        val expectedBytes = TsplGraphicEncoder.encode(
            FloydSteinbergBinarizer().binarize(page), target.widthMm, target.heightMm,
        )
        val transport = FakePrinterTransport.alwaysSucceeds()

        PrintEngine(transport).send(printer(), job(PrintPayloadType.TSPL, payload.payload))

        assertArrayEquals(expectedBytes, transport.sentPayloads.single())
    }

    private fun printer() = Printer(
        id = "p1", storeId = null, name = "TSC", model = PrinterModel.TSC_ML241, ipAddress = "10.0.0.5",
        port = 9100, protocol = PrinterProtocol.TCP, language = PrinterLanguage.TSPL, isDefault = true,
        enabled = true, lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )

    private fun job(type: PrintPayloadType, payload: String) = PrintJob(
        id = "j1", printerId = "p1", type = type, payload = payload, documentName = "doc", copies = 1,
        status = PrintJobStatus.PENDING, attempts = 0, maxAttempts = 5, errorMessage = null,
        externalDocumentId = null, originalJobId = null, createdAt = 0, startedAt = null, sentAt = null,
        updatedAt = 0, validationWarnings = emptyList(),
    )
}
