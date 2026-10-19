package br.com.atos.printbridge.domain.model.label.barcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EAN-13 do preview. O valor de referência `4006381333931` é um EAN-13 válido público (exemplo
 * canônico da Wikipédia), calculado fora desta implementação — é o que garante que o teste valida o
 * algoritmo, e não apenas a si mesmo.
 */
class Ean13EncoderTest {

    @Test
    fun `check digit is computed from the first twelve digits`() {
        assertEquals(1, Ean13Encoder.checkDigit("400638133393"))
    }

    @Test
    fun `valid thirteen digit code encodes to 95 modules with the guard bars`() {
        val modules = Ean13Encoder.encodeOrNull("4006381333931")!!

        assertEquals(Ean13Encoder.MODULE_COUNT, modules.size)
        assertEquals(listOf(true, false, true), modules.take(3))
        assertEquals(listOf(true, false, true), modules.takeLast(3))
    }

    @Test
    fun `twelve digits are completed with the computed check digit`() {
        val fromTwelve = Ean13Encoder.encodeOrNull("400638133393")
        val fromThirteen = Ean13Encoder.encodeOrNull("4006381333931")

        assertEquals(fromThirteen, fromTwelve)
    }

    @Test
    fun `wrong check digit is rejected`() {
        assertNull(Ean13Encoder.encodeOrNull("4006381333932"))
    }

    @Test
    fun `non numeric or wrong length is rejected`() {
        assertNull(Ean13Encoder.encodeOrNull(""))
        assertNull(Ean13Encoder.encodeOrNull("12345"))
        assertNull(Ean13Encoder.encodeOrNull("789100010010AB"))
        assertNull(Ean13Encoder.encodeOrNull("40063813339313"))
    }
}
