package br.com.atos.printbridge.printing.testpage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import br.com.atos.printbridge.domain.model.PwgMediaSize
import br.com.atos.printbridge.domain.printing.TestPageContent
import br.com.atos.printbridge.domain.printing.TestPageRenderer
import br.com.atos.printbridge.domain.raster.RasterSource
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Página de teste desenhada com o Canvas do Android: moldura a 10 mm da borda (mostra se a
 * impressora corta a margem), título e as linhas com os dados reais do envio.
 * PDF usa [PdfDocument] (unidade: pontos); raster usa um bitmap ALPHA_8 no dpi pedido.
 */
class AndroidTestPageRenderer @Inject constructor() : TestPageRenderer {

    override fun renderPdf(content: TestPageContent, media: PwgMediaSize): ByteArray {
        val widthPt = mmToUnits(media.widthMm, 72)
        val heightPt = mmToUnits(media.heightMm, 72)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(widthPt, heightPt, 1).create())
            draw(page.canvas, content, widthPt, heightPt, unitsPerMm = 72 / 25.4f)
            document.finishPage(page)
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    override fun renderRaster(content: TestPageContent, media: PwgMediaSize, dpi: Int): RasterSource {
        val width = mmToUnits(media.widthMm, dpi)
        val height = mmToUnits(media.heightMm, dpi)
        // ALPHA_8: 1 byte por pixel; o que é desenhado fica opaco (alpha 255 = preto).
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        draw(Canvas(bitmap), content, width, height, unitsPerMm = dpi / 25.4f)
        return object : RasterSource {
            private val row = IntArray(width)
            override val width: Int = width
            override val height: Int = height
            override fun readRow(y: Int, out: IntArray) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) out[x] = 255 - (row[x] ushr 24)
            }
        }
    }

    private fun draw(canvas: Canvas, content: TestPageContent, width: Int, height: Int, unitsPerMm: Float) {
        val margin = 10 * unitsPerMm
        val border = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.5f * unitsPerMm
        }
        canvas.drawRect(margin, margin, width - margin, height - margin, border)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 6 * unitsPerMm
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 4 * unitsPerMm
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val left = margin + 6 * unitsPerMm
        var y = margin + 14 * unitsPerMm
        canvas.drawText(content.title, left, y, title)
        y += 12 * unitsPerMm
        content.lines.forEach { line ->
            canvas.drawText(line, left, y, body)
            y += 7 * unitsPerMm
        }
    }

    private fun mmToUnits(mm: Double, unitsPerInch: Int): Int = Math.round(mm / 25.4 * unitsPerInch).toInt()
}
