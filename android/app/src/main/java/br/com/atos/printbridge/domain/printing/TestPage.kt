package br.com.atos.printbridge.domain.printing

import br.com.atos.printbridge.domain.model.PwgMediaSize
import br.com.atos.printbridge.domain.raster.RasterSource

/** Conteúdo da página de teste: só dados reais da impressora e do envio. */
data class TestPageContent(val title: String, val lines: List<String>)

/** Lado de plataforma: desenha a página de teste em PDF ou em raster no tamanho/dpi pedidos. */
interface TestPageRenderer {
    fun renderPdf(content: TestPageContent, media: PwgMediaSize): ByteArray
    fun renderRaster(content: TestPageContent, media: PwgMediaSize, dpi: Int): RasterSource
}
