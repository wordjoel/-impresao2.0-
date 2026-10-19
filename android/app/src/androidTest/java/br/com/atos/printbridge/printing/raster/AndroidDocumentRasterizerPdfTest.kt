package br.com.atos.printbridge.printing.raster

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Caminho PDF da Etapa 4 no `PdfRenderer` REAL (pdfium do aparelho). Não roda em Robolectric: lá o
 * PdfRenderer é um stub que sempre reporta 0 páginas. Rodar no coletor/emulador com
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDocumentRasterizerPdfTest {

    private val rasterizer = AndroidDocumentRasterizer(ApplicationProvider.getApplicationContext())
    private val target = RasterTarget.forLabel(widthMm = 100, heightMm = 72, dpi = 203)

    private fun darkRatio(image: GrayImage, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var dark = 0
        for (y in y0 until y1) for (x in x0 until x1) if (image[x, y] < 128) dark++
        return dark.toDouble() / ((x1 - x0) * (y1 - y0))
    }

    @Test
    fun threePagePdfYieldsThreeLabelSizedPagesInOrder() = runBlocking {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK }
        repeat(3) { index ->
            // 200x144pt tem a mesma proporção de 100x72mm: a página ocupa a etiqueta inteira.
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 144, index + 1).create())
            if (index != 1) page.canvas.drawRect(0f, 0f, 200f, 72f, paint) // metade de cima preta
            pdf.finishPage(page)
        }
        val bytes = ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()

        val pages = rasterizer.rasterize(bytes, DocumentKind.PDF, target)

        assertEquals(3, pages.size)
        pages.forEach { assertEquals(target.widthDots to target.heightDots, it.width to it.height) }
        assertTrue(darkRatio(pages[0], 50, 700, 20, 250) > 0.9)
        assertTrue(darkRatio(pages[0], 50, 700, 320, 540) < 0.05)
        assertTrue(darkRatio(pages[1], 0, target.widthDots, 0, target.heightDots) < 0.01)
        assertTrue(darkRatio(pages[2], 50, 700, 20, 250) > 0.9)
    }

    @Test
    fun corruptedPdfFailsWithClearError() {
        assertThrows(DocumentRasterizationException::class.java) {
            runBlocking { rasterizer.rasterize("%PDF-1.4 lixo".toByteArray(), DocumentKind.PDF, target) }
        }
    }
}
