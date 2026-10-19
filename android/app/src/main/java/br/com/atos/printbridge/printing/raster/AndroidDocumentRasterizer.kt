package br.com.atos.printbridge.printing.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.GrayImage
import br.com.atos.printbridge.domain.raster.RasterTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Rasterização com o que o próprio Android já traz — `PdfRenderer` (API 21+) e `BitmapFactory` —
 * sem biblioteca de PDF de terceiros. Cada página sai no tamanho EXATO da etiqueta
 * ([RasterTarget.widthDots] x [RasterTarget.heightDots]): o conteúdo é encaixado mantendo a
 * proporção, centralizado na horizontal e alinhado ao topo, sobre fundo branco. Assim ^PW/^LL
 * (ZPL) e SIZE (TSPL) batem sempre com o papel do perfil, qualquer que seja o formato da página.
 */
class AndroidDocumentRasterizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentRasterizer {

    override suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage> =
        withContext(Dispatchers.Default) {
            if (document.isEmpty()) throw DocumentRasterizationException("Documento vazio")
            if (document.size > DocumentRasterizer.MAX_DOCUMENT_BYTES) {
                throw DocumentRasterizationException(
                    "Documento de ${document.size} bytes excede o limite de ${DocumentRasterizer.MAX_DOCUMENT_BYTES} bytes",
                )
            }
            when (kind) {
                DocumentKind.PDF -> mutableListOf<GrayImage>().also { pages -> forEachPdfPage(document, target) { _, _, page -> pages += page } }
                DocumentKind.IMAGE -> listOf(rasterizeImage(document, target))
            }
        }

    override suspend fun rasterizePages(
        document: ByteArray,
        kind: DocumentKind,
        target: RasterTarget,
        onPage: suspend (index: Int, total: Int, page: GrayImage) -> Unit,
    ) = withContext(Dispatchers.Default) {
        if (document.isEmpty()) throw DocumentRasterizationException("Documento vazio")
        if (document.size > DocumentRasterizer.MAX_DOCUMENT_BYTES) {
            throw DocumentRasterizationException(
                "Documento de ${document.size} bytes excede o limite de ${DocumentRasterizer.MAX_DOCUMENT_BYTES} bytes",
            )
        }
        when (kind) {
            // Uma página viva por vez: a anterior já foi entregue e pode ser coletada.
            DocumentKind.PDF -> forEachPdfPage(document, target, onPage)
            DocumentKind.IMAGE -> onPage(0, 1, rasterizeImage(document, target))
        }
    }

    private suspend fun forEachPdfPage(
        document: ByteArray,
        target: RasterTarget,
        onPage: suspend (index: Int, total: Int, page: GrayImage) -> Unit,
    ) {
        // PdfRenderer só aceita ParcelFileDescriptor "seekable" — um arquivo temporário no cache
        // privado do app, apagado no finally (o PDF pode conter dado de negócio).
        val temp = File.createTempFile("raster-", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(document)
            val fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: IOException) {
                fd.close()
                throw DocumentRasterizationException("PDF inválido ou protegido por senha", e)
            } catch (e: SecurityException) {
                fd.close()
                throw DocumentRasterizationException("PDF protegido por senha", e)
            }
            renderer.use { pdf ->
                if (pdf.pageCount == 0) throw DocumentRasterizationException("PDF sem páginas")
                if (pdf.pageCount > DocumentRasterizer.MAX_PAGES) {
                    throw DocumentRasterizationException(
                        "PDF com ${pdf.pageCount} páginas excede o limite de ${DocumentRasterizer.MAX_PAGES}",
                    )
                }
                for (index in 0 until pdf.pageCount) {
                    val gray = pdf.openPage(index).use { page ->
                        val (w, h) = target.fit(page.width, page.height)
                        val content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // Página PDF é transparente por padrão: sem o branco, o fundo viraria "preto"
                        // na binarização (alfa 0 lido como luminância 0).
                        content.eraseColor(Color.WHITE)
                        page.render(content, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        placeOnLabel(content, target)
                    }
                    onPage(index, pdf.pageCount, gray)
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun rasterizeImage(document: ByteArray, target: RasterTarget): GrayImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(document, 0, document.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw DocumentRasterizationException("Imagem ilegível ou formato não suportado")
        }
        // Subamostragem na decodificação: uma foto de 12MP nunca é carregada inteira só para
        // virar uma etiqueta de 800 pontos.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target.widthDots && bounds.outHeight / (sample * 2) >= target.heightDots) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            document, 0, document.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: throw DocumentRasterizationException("Imagem ilegível ou formato não suportado")
        return placeOnLabel(decoded, target)
    }

    /** Desenha [content] encaixado numa tela branca do tamanho da etiqueta e devolve em cinza. */
    private fun placeOnLabel(content: Bitmap, target: RasterTarget): GrayImage {
        val (w, h) = target.fit(content.width, content.height)
        val label = Bitmap.createBitmap(target.widthDots, target.heightDots, Bitmap.Config.ARGB_8888)
        label.eraseColor(Color.WHITE)
        val left = (target.widthDots - w) / 2
        Canvas(label).drawBitmap(
            content,
            null,
            Rect(left, 0, left + w, h),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        content.recycle()

        val pixels = IntArray(target.widthDots * target.heightDots)
        label.getPixels(pixels, 0, target.widthDots, 0, 0, target.widthDots, target.heightDots)
        label.recycle()
        return GrayImage(target.widthDots, target.heightDots, IntArray(pixels.size) { luminance(pixels[it]) })
    }

    /** Luminância Rec. 601 com o alfa composto sobre branco (PNG transparente = papel, não tinta). */
    private fun luminance(argb: Int): Int {
        val a = argb ushr 24 and 0xFF
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        val gray = (r * 299 + g * 587 + b * 114) / 1000
        return (gray * a + 255 * (255 - a)) / 255
    }
}
