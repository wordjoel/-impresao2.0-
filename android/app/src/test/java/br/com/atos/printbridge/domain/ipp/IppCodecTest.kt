package br.com.atos.printbridge.domain.ipp

import br.com.atos.printbridge.domain.model.PrinterCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class IppCodecTest {

    @Test
    fun `get printer attributes request is encoded exactly as RFC 8010`() {
        val bytes = IppCodec.encodeRequest(
            IppCodec.OP_GET_PRINTER_ATTRIBUTES,
            7,
            listOf(
                IppCodec.Group(
                    IppCodec.TAG_OPERATION,
                    listOf(
                        IppCodec.Attr(IppCodec.TAG_CHARSET, "attributes-charset", "utf-8"),
                        IppCodec.Attr(IppCodec.TAG_KEYWORD, "requested-attributes", listOf("a", "b")),
                    ),
                ),
            ),
        )
        val expected = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).apply {
                write(byteArrayOf(1, 1, 0x00, 0x0B, 0, 0, 0, 7, 0x01))
                writeByte(0x47); writeShort(18); writeBytes("attributes-charset"); writeShort(5); writeBytes("utf-8")
                writeByte(0x44); writeShort(20); writeBytes("requested-attributes"); writeShort(1); writeBytes("a")
                // Segundo valor do mesmo atributo: nome vazio.
                writeByte(0x44); writeShort(0); writeShort(1); writeBytes("b")
                writeByte(0x03)
            }
        }.toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `integer and copies are encoded as 4 byte values`() {
        val bytes = IppCodec.encodeRequest(
            IppCodec.OP_PRINT_JOB, 1,
            listOf(IppCodec.Group(IppCodec.TAG_JOB, listOf(IppCodec.Attr(IppCodec.TAG_INTEGER, "copies", 3)))),
        )
        // cabeçalho(8) + grupo(1) + tag(1) + len(2) + "copies"(6) + len(2) + int(4) + fim(1)
        assertEquals(25, bytes.size)
        assertArrayEquals(byteArrayOf(0, 4, 0, 0, 0, 3), bytes.copyOfRange(18, 24))
    }

    /** Monta uma resposta como uma impressora mandaria, incluindo coleção e texto com idioma. */
    private fun printerResponse(): ByteArray = ByteArrayOutputStream().also { raw ->
        DataOutputStream(raw).apply {
            write(byteArrayOf(2, 0, 0x00, 0x00, 0, 0, 0, 7))
            writeByte(0x01)
            attr(0x47, "attributes-charset", "utf-8")
            writeByte(0x04)
            attr(0x41, "printer-make-and-model", "Samsung M2070 Series")
            attr(0x41, "printer-device-id", "MFG:Samsung;MDL:M2070 Series;CMD:SPL,URF,PWGRaster;")
            attr(0x42, "printer-name", "SEC30CDA71C5205")
            writeByte(0x23); writeShort("printer-state".length); writeBytes("printer-state"); writeShort(4); writeInt(3)
            attr(0x44, "printer-state-reasons", "none")
            writeByte(0x22); writeShort("printer-is-accepting-jobs".length); writeBytes("printer-is-accepting-jobs"); writeShort(1); writeByte(1)
            attr(0x49, "document-format-supported", "application/octet-stream")
            attr(0x49, "", "image/pwg-raster")
            attr(0x49, "", "image/urf")
            // Coleção no meio (media-col-default) — não pode vazar para os atributos de topo.
            writeByte(0x34); writeShort("media-col-default".length); writeBytes("media-col-default"); writeShort(0)
            writeByte(0x4A); writeShort(0); writeShort(10); writeBytes("media-size")
            writeByte(0x34); writeShort(0); writeShort(0)
            writeByte(0x4A); writeShort(0); writeShort(11); writeBytes("x-dimension")
            writeByte(0x21); writeShort(0); writeShort(4); writeInt(21000)
            writeByte(0x37); writeShort(0); writeShort(0)
            writeByte(0x37); writeShort(0); writeShort(0)
            attr(0x44, "media-supported", "iso_a4_210x297mm")
            attr(0x44, "", "na_letter_8.5x11in")
            attr(0x44, "media-default", "iso_a4_210x297mm")
            attr(0x44, "sides-supported", "one-sided")
            attr(0x44, "print-color-mode-supported", "monochrome")
            attr(0x44, "pwg-raster-document-type-supported", "black_1")
            attr(0x44, "", "sgray_8")
            res("pwg-raster-document-resolution-supported", 600, 3)
            res("printer-resolution-supported", 600, 3)
            res("", 236, 4) // 236 dpcm ≈ 600 dpi
            // text com idioma
            val lang = "pt-br".toByteArray(); val text = "Sala 2".toByteArray()
            writeByte(0x35); writeShort(16); writeBytes("printer-location"); writeShort(4 + lang.size + text.size)
            writeShort(lang.size); write(lang); writeShort(text.size); write(text)
            writeByte(0x03)
        }
    }.toByteArray()

    private fun DataOutputStream.attr(tag: Int, name: String, value: String) {
        writeByte(tag); writeShort(name.length); writeBytes(name)
        val v = value.toByteArray(Charsets.UTF_8); writeShort(v.size); write(v)
    }

    private fun DataOutputStream.res(name: String, dots: Int, units: Int) {
        writeByte(0x32); writeShort(name.length); writeBytes(name); writeShort(9); writeInt(dots); writeInt(dots); writeByte(units)
    }

    @Test
    fun `response attributes are decoded with additional values and collections skipped`() {
        val response = IppCodec.decodeResponse(printerResponse())

        assertTrue(response.isSuccess)
        assertEquals(7, response.requestId)
        assertEquals(listOf("application/octet-stream", "image/pwg-raster", "image/urf"), response.strings("document-format-supported"))
        assertEquals(3, response.int("printer-state"))
        assertEquals("Sala 2", response.string("printer-location"))
        assertNull("membro de coleção não é atributo de topo", response.string("x-dimension"))
        assertEquals(listOf(600, 600), response.resolutions("printer-resolution-supported").map { it.dpi })
    }

    @Test
    fun `printer description takes manufacturer and model from the device id`() {
        val d = IppPrinterDescription.from(IppCodec.decodeResponse(printerResponse()))

        assertEquals("Samsung", d.manufacturer)
        assertEquals("M2070 Series", d.model)
        assertEquals("SEC30CDA71C5205", d.name)
        assertEquals("Ociosa", d.stateLabel)
        assertTrue(d.problems.isEmpty())
        assertEquals(true, d.acceptingJobs)
        val caps = d.capabilities
        assertFalse(caps.supportsPdf)
        assertTrue(caps.supportsPwgRaster)
        assertFalse(caps.supportsDuplex)
        assertEquals(listOf(600), caps.resolutionsDpi)
        assertEquals("iso_a4_210x297mm", caps.defaultMedia)
        assertEquals(listOf("black_1", "sgray_8"), caps.pwgRasterTypes)
    }

    @Test
    fun `without device id the model is make and model and manufacturer is not guessed`() {
        val raw = ByteArrayOutputStream().also { r ->
            DataOutputStream(r).apply {
                write(byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1)); writeByte(0x04)
                attr(0x41, "printer-make-and-model", "HP LaserJet Pro M203dw")
                writeByte(0x03)
            }
        }.toByteArray()
        val d = IppPrinterDescription.from(IppCodec.decodeResponse(raw))
        assertNull(d.manufacturer)
        assertEquals("HP LaserJet Pro M203dw", d.model)
        assertEquals("Não informado", d.stateLabel)
    }

    @Test
    fun `error status and truncated responses are reported`() {
        val error = IppCodec.decodeResponse(byteArrayOf(1, 1, 0x04, 0x0A, 0, 0, 0, 1, 0x03))
        assertFalse(error.isSuccess)
        assertEquals("client-error-document-format-not-supported", IppCodec.statusName(error.statusCode))

        val truncated = runCatching { IppCodec.decodeResponse(byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 0x04, 0x41, 0, 5, 'a'.code.toByte())) }
        assertTrue(truncated.exceptionOrNull() is IppCodec.MalformedResponseException)
    }

    @Test
    fun `capabilities survive the room serialization`() {
        val caps = IppPrinterDescription.from(IppCodec.decodeResponse(printerResponse())).capabilities
        assertEquals(caps, PrinterCapabilities.decode(caps.encode()))
        assertNull(PrinterCapabilities.decode(null))
    }

    @Test
    fun `endpoint builds uri and http path`() {
        val e = IppEndpoint("192.168.15.60", 631, "/ipp/print")
        assertEquals("ipp://192.168.15.60:631/ipp/print", e.uri)
        assertEquals("http://192.168.15.60:631/ipp/print", e.httpUrl)
        assertEquals("ipp://10.0.0.1:631/", IppEndpoint("10.0.0.1", 631, "").uri)
    }
}
