package br.com.atos.printbridge.domain.raster

import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.model.PrinterLanguage

enum class DocumentKind { PDF, IMAGE }

/**
 * Área útil da etiqueta em pontos da impressora. Largura sempre múltipla de 8 (a linha do gráfico
 * é em bytes inteiros) e arredondada para BAIXO — nunca desenhar além da cabeça de impressão.
 */
data class RasterTarget(val widthDots: Int, val heightDots: Int, val widthMm: Int, val heightMm: Int) {
    companion object {
        fun forLabel(widthMm: Int, heightMm: Int, dpi: Int): RasterTarget {
            require(widthMm > 0 && heightMm > 0) { "Tamanho de papel inválido: ${widthMm}x$heightMm mm" }
            require(dpi > 0) { "DPI inválido: $dpi" }
            val width = (widthMm * dpi / 25.4).toInt() / 8 * 8
            val height = (heightMm * dpi / 25.4).toInt()
            return RasterTarget(width, height, widthMm, heightMm)
        }
    }

    /**
     * Encaixa uma página de [srcWidth]x[srcHeight] na etiqueta mantendo a proporção ("ajustar à
     * página"), como fazia o Print Framework no fluxo antigo — nunca distorce nem corta conteúdo.
     */
    fun fit(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        require(srcWidth > 0 && srcHeight > 0) { "Página vazia: ${srcWidth}x$srcHeight" }
        val scale = minOf(widthDots.toDouble() / srcWidth, heightDots.toDouble() / srcHeight)
        return (srcWidth * scale).toInt().coerceAtLeast(1) to (srcHeight * scale).toInt().coerceAtLeast(1)
    }
}

/** Lado de plataforma: abre PDF/imagem e devolve cada página já na resolução do alvo, em cinza. */
interface DocumentRasterizer {
    /** @throws DocumentRasterizationException documento ilegível, vazio ou acima dos limites. */
    suspend fun rasterize(document: ByteArray, kind: DocumentKind, target: RasterTarget): List<GrayImage>

    /**
     * Página a página: cada página é entregue a [onPage] e pode ser descartada antes da próxima ser
     * rasterizada — necessário para páginas A4 em impressoras de escritório (uma página a 300 dpi
     * em cinza ocupa ~35 MB). O padrão delega a [rasterize] (suficiente para etiquetas e testes).
     */
    suspend fun rasterizePages(
        document: ByteArray,
        kind: DocumentKind,
        target: RasterTarget,
        onPage: suspend (index: Int, total: Int, page: GrayImage) -> Unit,
    ) {
        val pages = rasterize(document, kind, target)
        pages.forEachIndexed { i, page -> onPage(i, pages.size, page) }
    }

    companion object {
        /** Teto por documento — um PDF de 500 páginas enviado por engano não pode travar a fila da loja. */
        const val MAX_PAGES = 50
        const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024
    }
}

/** Uma página já pronta para a fila: texto ZPL, ou TSPL binário guardado como ISO-8859-1. */
data class RasterPagePayload(val language: PrinterLanguage, val payload: String)

/**
 * Parte pura da Etapa 4: páginas em cinza → 1 bit → comando gráfico nativo da impressora.
 * Uma página = um payload = um job da fila, na ordem original (PDF multipágina vira N jobs).
 */
object RasterPageEncoder {
    fun encode(
        pages: List<GrayImage>,
        language: PrinterLanguage,
        target: RasterTarget,
        dither: DitherMode,
    ): List<RasterPagePayload> {
        val binarizer = dither.binarizer()
        return pages.map { page ->
            val mono = binarizer.binarize(page)
            when (language) {
                PrinterLanguage.TSPL -> RasterPagePayload(
                    PrinterLanguage.TSPL,
                    String(TsplGraphicEncoder.encode(mono, target.widthMm, target.heightMm), Charsets.ISO_8859_1),
                )
                // AUTO segue o mesmo default do resto do app (mapper de Printer): ZPL.
                PrinterLanguage.ZPL, PrinterLanguage.AUTO -> RasterPagePayload(PrinterLanguage.ZPL, ZplGraphicEncoder.encode(mono))
            }
        }
    }
}
