package br.com.atos.printbridge.domain.raster

/**
 * Página rasterizada em tons de cinza (0 = preto, 255 = branco), linha a linha. Tipo puro de
 * domínio: o lado Android (PdfRenderer/BitmapFactory) produz isto, e todo o resto da cadeia
 * (binarização, codificação ZPL/TSPL) roda e é testado em JVM pura.
 */
class GrayImage(val width: Int, val height: Int, val luminance: IntArray) {
    init {
        require(width > 0 && height > 0) { "Imagem vazia: ${width}x$height" }
        require(luminance.size == width * height) { "Esperado ${width * height} pixels, veio ${luminance.size}" }
    }

    operator fun get(x: Int, y: Int): Int = luminance[y * width + x]
}

/**
 * Imagem de 1 bit por ponto, empacotada como a impressora térmica espera: cada linha ocupa
 * [bytesPerRow] bytes, bit mais significativo = ponto mais à esquerda, **bit 1 = ponto queimado
 * (preto)**. Colunas de preenchimento no fim da linha são sempre 0 (branco).
 */
class MonoBitmap(val width: Int, val height: Int, val data: ByteArray) {
    val bytesPerRow: Int = (width + 7) / 8

    init {
        require(width > 0 && height > 0) { "Imagem vazia: ${width}x$height" }
        require(data.size == bytesPerRow * height) { "Esperado ${bytesPerRow * height} bytes, veio ${data.size}" }
    }

    fun isBlack(x: Int, y: Int): Boolean =
        (data[y * bytesPerRow + x / 8].toInt() shr (7 - x % 8)) and 1 == 1

    companion object {
        /** Monta a partir de uma matriz booleana linha a linha (true = preto) — útil para testes e encoders. */
        fun fromRows(width: Int, height: Int, isBlack: (x: Int, y: Int) -> Boolean): MonoBitmap {
            val bytesPerRow = (width + 7) / 8
            val data = ByteArray(bytesPerRow * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (isBlack(x, y)) {
                        val index = y * bytesPerRow + x / 8
                        data[index] = (data[index].toInt() or (0x80 ushr (x % 8))).toByte()
                    }
                }
            }
            return MonoBitmap(width, height, data)
        }
    }
}
