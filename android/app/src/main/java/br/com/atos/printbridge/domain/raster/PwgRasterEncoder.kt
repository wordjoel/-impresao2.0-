package br.com.atos.printbridge.domain.raster

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Uma página lida linha a linha (luminância 0 = preto, 255 = branco), sem exigir a página inteira na memória. */
interface RasterSource {
    val width: Int
    val height: Int
    fun readRow(y: Int, out: IntArray)
}

fun GrayImage.asRasterSource(): RasterSource = object : RasterSource {
    override val width: Int get() = this@asRasterSource.width
    override val height: Int get() = this@asRasterSource.height
    override fun readRow(y: Int, out: IntArray) {
        System.arraycopy(luminance, y * width, out, 0, width)
    }
}

/** Imagem já binarizada (Fase 1) como fonte de linhas: preto = 0, branco = 255. */
fun MonoBitmap.asGraySource(): RasterSource = object : RasterSource {
    override val width: Int get() = this@asGraySource.width
    override val height: Int get() = this@asGraySource.height
    override fun readRow(y: Int, out: IntArray) {
        for (x in 0 until width) out[x] = if (isBlack(x, y)) 0 else 255
    }
}

/** Tipos de PWG Raster gerados: 1 bit preto (1 = queima) ou cinza 8 bits (0 = preto). */
enum class PwgRasterType(val keyword: String, val bitsPerPixel: Int, val colorSpace: Int) {
    BLACK_1("black_1", 1, 3),
    SGRAY_8("sgray_8", 8, 18),
    ;

    companion object {
        /** Prefere 1 bit (menor e mais nítido para texto); cinza só se a impressora não aceitar 1 bit. */
        fun preferredOf(supported: List<String>): PwgRasterType? =
            entries.firstOrNull { it.keyword in supported }
    }
}

/**
 * PWG Raster (PWG 5102.4): "RaS2" + por página um cabeçalho de 1796 bytes e as linhas comprimidas
 * (repetição de linha + PackBits por byte). É o formato "raster" do IPP Everywhere/Mopria, aceito
 * por impressoras que não interpretam PDF.
 */
object PwgRasterEncoder {
    const val HEADER_SIZE = 1796

    fun encode(
        pages: List<RasterSource>,
        type: PwgRasterType,
        dpi: Int,
        mediaName: String?,
    ): ByteArray {
        require(pages.isNotEmpty()) { "Nenhuma página para codificar" }
        val stream = Stream(type, dpi, mediaName, pages.size)
        pages.forEach(stream::addPage)
        return stream.toByteArray()
    }

    /**
     * Codificação incremental: cada página é comprimida ao ser adicionada, então a página crua pode
     * ser liberada antes da próxima — o documento na memória é só o resultado comprimido.
     */
    class Stream(private val type: PwgRasterType, private val dpi: Int, private val mediaName: String?, private val totalPages: Int) {
        private val out = ByteArrayOutputStream()

        init {
            require(dpi > 0) { "DPI inválido: $dpi" }
            out.write("RaS2".toByteArray(Charsets.US_ASCII))
        }

        fun addPage(page: RasterSource) {
            out.write(header(page, type, dpi, mediaName, totalPages))
            writePage(out, page, type)
        }

        val size: Int get() = out.size()

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    fun bytesPerLine(width: Int, type: PwgRasterType): Int = (width * type.bitsPerPixel + 7) / 8

    private fun header(page: RasterSource, type: PwgRasterType, dpi: Int, mediaName: String?, totalPages: Int): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE)
        putString(b, 0, "PwgRaster")
        b.putInt(276, dpi) // HWResolution x
        b.putInt(280, dpi) // HWResolution y
        b.putInt(340, 1) // NumCopies — cópias vão pelo atributo IPP "copies"
        // PageSize em pontos (1/72"), arredondado.
        b.putInt(352, Math.round(page.width * 72f / dpi))
        b.putInt(356, Math.round(page.height * 72f / dpi))
        b.putInt(372, page.width) // cupsWidth
        b.putInt(376, page.height) // cupsHeight
        b.putInt(384, type.bitsPerPixel) // cupsBitsPerColor
        b.putInt(388, type.bitsPerPixel) // cupsBitsPerPixel
        b.putInt(392, bytesPerLine(page.width, type))
        b.putInt(396, 0) // cupsColorOrder: chunky
        b.putInt(400, type.colorSpace)
        b.putInt(420, 1) // cupsNumColors
        b.putInt(452, totalPages) // TotalPageCount (cupsInteger[0])
        b.putInt(456, 1) // CrossFeedTransform
        b.putInt(460, 1) // FeedTransform
        b.putInt(472, page.width) // ImageBoxRight
        b.putInt(476, page.height) // ImageBoxBottom
        mediaName?.let { putString(b, 1732, it) } // cupsPageSizeName
        return b.array()
    }

    private fun putString(b: ByteBuffer, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII).take(63)
        bytes.forEachIndexed { i, byte -> b.put(offset + i, byte) }
    }

    private fun writePage(out: ByteArrayOutputStream, page: RasterSource, type: PwgRasterType) {
        val luminance = IntArray(page.width)
        val lineBytes = bytesPerLine(page.width, type)
        var previous: ByteArray? = null
        var repeat = 0
        for (y in 0 until page.height) {
            page.readRow(y, luminance)
            val line = packLine(luminance, page.width, lineBytes, type)
            if (previous != null && repeat < 255 && line.contentEquals(previous)) {
                repeat++
                continue
            }
            previous?.let { writeLine(out, it, repeat) }
            previous = line
            repeat = 0
        }
        previous?.let { writeLine(out, it, repeat) }
    }

    /** Linha → bytes crus do tipo: black_1 com bit 1 = preto; sgray_8 = a própria luminância. */
    fun packLine(luminance: IntArray, width: Int, lineBytes: Int, type: PwgRasterType): ByteArray {
        val line = ByteArray(lineBytes)
        when (type) {
            PwgRasterType.BLACK_1 -> for (x in 0 until width) {
                if (luminance[x] < 128) {
                    val i = x ushr 3
                    line[i] = (line[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            PwgRasterType.SGRAY_8 -> for (x in 0 until width) line[x] = luminance[x].coerceIn(0, 255).toByte()
        }
        return line
    }

    /** [repeat] linhas extras idênticas + PackBits por byte (0..127 repete, 129..255 literal). */
    fun writeLine(out: ByteArrayOutputStream, line: ByteArray, repeat: Int) {
        out.write(repeat)
        var i = 0
        while (i < line.size) {
            var run = 1
            while (i + run < line.size && run < 128 && line[i + run] == line[i]) run++
            if (run > 1) {
                out.write(run - 1)
                out.write(line[i].toInt())
                i += run
            } else {
                // Literal: até 128 bytes enquanto não começar uma repetição.
                var len = 1
                while (i + len < line.size && len < 128 &&
                    !(i + len + 1 < line.size && line[i + len] == line[i + len + 1])
                ) len++
                if (len == 1) {
                    // Literal vai de 2 a 128 bytes (129..255); byte solto é "repete 1 vez" (0).
                    out.write(0)
                    out.write(line[i].toInt())
                } else {
                    out.write(257 - len)
                    out.write(line, i, len)
                }
                i += len
            }
        }
    }
}
