package br.com.atos.printbridge.domain.raster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PwgRasterEncoderTest {

    private fun source(width: Int, height: Int, pixel: (Int, Int) -> Int) = object : RasterSource {
        override val width = width
        override val height = height
        override fun readRow(y: Int, out: IntArray) { for (x in 0 until width) out[x] = pixel(x, y) }
    }

    /** Decodificador independente (PWG 5102.4 §4.3) para conferir o encoder por ida e volta. */
    private fun decodePage(data: ByteArray, offset: Int, lineBytes: Int, height: Int): Pair<List<ByteArray>, Int> {
        var pos = offset
        val lines = mutableListOf<ByteArray>()
        while (lines.size < height) {
            val repeat = data[pos++].toInt() and 0xFF
            val line = ByteArray(lineBytes)
            var i = 0
            while (i < lineBytes) {
                val c = data[pos++].toInt() and 0xFF
                if (c <= 127) {
                    val b = data[pos++]
                    repeat(c + 1) { line[i++] = b }
                } else {
                    val n = 257 - c
                    System.arraycopy(data, pos, line, i, n); pos += n; i += n
                }
            }
            repeat(repeat + 1) { lines += line }
        }
        return lines to pos
    }

    @Test
    fun `black_1 page round trips and header carries size resolution and media`() {
        // Página 20x6: moldura preta, miolo branco, uma coluna preta no x=10 — mistura repetições e literais.
        val page = source(20, 6) { x, y -> if (y == 0 || y == 5 || x == 0 || x == 19 || x == 10) 0 else 255 }
        val data = PwgRasterEncoder.encode(listOf(page), PwgRasterType.BLACK_1, 300, "iso_a4_210x297mm")

        assertEquals("RaS2", String(data, 0, 4, Charsets.US_ASCII))
        val h = ByteBuffer.wrap(data, 4, PwgRasterEncoder.HEADER_SIZE).slice()
        assertEquals("PwgRaster", String(data, 4, 9, Charsets.US_ASCII))
        assertEquals(300, h.getInt(276))
        assertEquals(20, h.getInt(372))
        assertEquals(6, h.getInt(376))
        assertEquals(1, h.getInt(388))
        assertEquals(3, h.getInt(392)) // 20 px → 3 bytes
        assertEquals(3, h.getInt(400)) // black
        assertEquals("iso_a4_210x297mm", String(data, 4 + 1732, 16, Charsets.US_ASCII))

        val (lines, end) = decodePage(data, 4 + PwgRasterEncoder.HEADER_SIZE, 3, 6)
        assertEquals(data.size, end)
        val full = PwgRasterEncoder.packLine(IntArray(20) { 0 }, 20, 3, PwgRasterType.BLACK_1)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()), full)
        assertArrayEquals(full, lines[0])
        // x=0, x=10, x=19 pretos: 1000_0000 0010_0000 0001_0000
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x20, 0x10), lines[1])
        assertArrayEquals(lines[1], lines[4])
    }

    @Test
    fun `sgray_8 keeps luminance and multiple pages keep order`() {
        val p1 = source(4, 2) { x, _ -> x * 60 }
        val p2 = source(4, 2) { _, _ -> 255 }
        val data = PwgRasterEncoder.encode(listOf(p1, p2), PwgRasterType.SGRAY_8, 600, null)
        val first = 4 + PwgRasterEncoder.HEADER_SIZE
        assertEquals(2, ByteBuffer.wrap(data).getInt(4 + 452)) // TotalPageCount
        val (lines1, end1) = decodePage(data, first, 4, 2)
        assertArrayEquals(byteArrayOf(0, 60, 120, 180.toByte()), lines1[0])
        val (lines2, end2) = decodePage(data, end1 + PwgRasterEncoder.HEADER_SIZE, 4, 2)
        assertTrue(lines2.all { l -> l.all { it == 255.toByte() } })
        assertEquals(data.size, end2)
    }

    @Test
    fun `blank page compresses to almost nothing`() {
        val a4At300 = source(2480, 3508) { _, _ -> 255 }
        val data = PwgRasterEncoder.encode(listOf(a4At300), PwgRasterType.BLACK_1, 300, "iso_a4_210x297mm")
        // 3508 linhas iguais = 14 grupos de 256 linhas; cada grupo ~ 1 + 3*2 bytes.
        assertTrue("tamanho ${data.size}", data.size < PwgRasterEncoder.HEADER_SIZE + 200)
    }

    @Test
    fun `single isolated bytes never use the reserved literal of length one`() {
        val out = java.io.ByteArrayOutputStream()
        PwgRasterEncoder.writeLine(out, byteArrayOf(1, 2, 2, 3), 0)
        // repeat=0 | 0x00 0x01 (1 byte) | 0x01 0x02 (2x) | 0x00 0x03
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 2, 0, 3), out.toByteArray())
    }

    @Test
    fun `preferred type follows what the printer declares`() {
        assertEquals(PwgRasterType.BLACK_1, PwgRasterType.preferredOf(listOf("sgray_8", "black_1")))
        assertEquals(PwgRasterType.SGRAY_8, PwgRasterType.preferredOf(listOf("srgb_8", "sgray_8")))
        assertEquals(null, PwgRasterType.preferredOf(listOf("srgb_8")))
    }
}
