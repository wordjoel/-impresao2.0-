package br.com.atos.printbridge.printing.raster

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDocumentRasterizerTest {

    private val rasterizer = AndroidDocumentRasterizer(ApplicationProvider.getApplicationContext())
    private val target = RasterTarget.forLabel(widthMm = 100, heightMm = 72, dpi = 203)

    private fun darkRatio(image: GrayImage, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var dark = 0
        for (y in y0 until y1) for (x in x0 until x1) if (image[x, y] < 128) dark++
        return dark.toDouble() / ((x1 - x0) * (y1 - y0))
    }

    @Test
    fun `png with black left half comes out label sized with dark left and white right`() = runTest {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 0 until 100) for (x in 0 until 100) setPixel(x, y, Color.BLACK)
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val page = rasterizer.rasterize(png, DocumentKind.IMAGE, target).single()

        assertEquals(target.widthDots, page.width)
        assertEquals(target.heightDots, page.height)
        // 200x100 encaixa por largura: 792x396, alinhado ao topo.
        assertTrue(darkRatio(page, 10, 380, 10, 380) > 0.95)
        assertTrue(darkRatio(page, 410, 780, 10, 380) < 0.05)
        assertTrue(darkRatio(page, 0, target.widthDots, 400, target.heightDots) < 0.01)
    }

    @Test
    fun `transparent png pixels are treated as paper not ink`() = runTest {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val page = rasterizer.rasterize(png, DocumentKind.IMAGE, target).single()

        assertTrue(darkRatio(page, 0, page.width, 0, page.height) < 0.01)
    }

    @Test
    fun `garbage image bytes fail with a clear error instead of printing noise`() {
        assertThrows(DocumentRasterizationException::class.java) {
            kotlinx.coroutines.runBlocking {
                rasterizer.rasterize(byteArrayOf(1, 2, 3, 4), DocumentKind.IMAGE, target)
            }
        }
    }
}
