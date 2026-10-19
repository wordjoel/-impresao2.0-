package br.com.atos.printbridge.domain.raster

/**
 * Converte tons de cinza em 1 bit. É o ponto de extensão pedido na Etapa 4: novos algoritmos
 * (Atkinson, ordered dither...) entram implementando esta interface, sem mudar a API pública.
 */
interface Binarizer {
    fun binarize(image: GrayImage): MonoBitmap
}

enum class DitherMode {
    /** Corte simples — o melhor para texto/código de barras de PDF (bordas nítidas, sem ruído). */
    THRESHOLD,

    /** Difusão de erro — preserva gradientes de foto/logo, mas "suja" texto fino. */
    FLOYD_STEINBERG;

    fun binarizer(): Binarizer = when (this) {
        THRESHOLD -> ThresholdBinarizer()
        FLOYD_STEINBERG -> FloydSteinbergBinarizer()
    }
}

class ThresholdBinarizer(private val threshold: Int = DEFAULT_THRESHOLD) : Binarizer {
    override fun binarize(image: GrayImage): MonoBitmap =
        MonoBitmap.fromRows(image.width, image.height) { x, y -> image[x, y] < threshold }
}

/**
 * Floyd–Steinberg clássico (7/16, 3/16, 5/16, 1/16), varredura esquerda→direita. Trabalha numa
 * cópia do buffer — a [GrayImage] de entrada nunca é alterada.
 */
class FloydSteinbergBinarizer(private val threshold: Int = DEFAULT_THRESHOLD) : Binarizer {
    override fun binarize(image: GrayImage): MonoBitmap {
        val w = image.width
        val h = image.height
        val buffer = IntArray(w * h) { image.luminance[it] }
        val black = BooleanArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = buffer[i]
                val isBlack = old < threshold
                black[i] = isBlack
                val error = old - (if (isBlack) 0 else 255)
                if (x + 1 < w) buffer[i + 1] += error * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) buffer[i + w - 1] += error * 3 / 16
                    buffer[i + w] += error * 5 / 16
                    if (x + 1 < w) buffer[i + w + 1] += error * 1 / 16
                }
            }
        }
        return MonoBitmap.fromRows(w, h) { x, y -> black[y * w + x] }
    }
}

const val DEFAULT_THRESHOLD = 128
