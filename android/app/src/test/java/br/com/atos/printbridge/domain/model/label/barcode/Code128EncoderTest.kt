package br.com.atos.printbridge.domain.model.label.barcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code 128 do preview Compose. A impressão real usa `^BC` da impressora; este encoder existe para
 * a pré-visualização não ser um retângulo vazio. Os testes verificam a ESTRUTURA do símbolo
 * (código de início + dados + checksum + stop = 11 módulos por código, stop com 13), que é o que
 * denuncia um erro de tabela/checksum.
 */
class Code128EncoderTest {

    @Test
    fun `encoding uses 11 modules per symbol plus the 13-module stop`() {
        val modules = Code128Encoder.encodeOrNull("ABC")

        // START + 3 dados + checksum = 5 códigos de 11 módulos, mais o STOP de 13.
        assertEquals(5 * 11 + 13, modules?.size)
    }

    @Test
    fun `symbol starts and ends with a bar`() {
        val modules = Code128Encoder.encodeOrNull("ABC")!!

        assertTrue(modules.first())
        assertTrue(modules.last())
    }

    @Test
    fun `single character encoding has four symbols`() {
        val modules = Code128Encoder.encodeOrNull("A")

        assertEquals(3 * 11 + 13, modules?.size)
    }

    @Test
    fun `empty or non ascii values are rejected`() {
        assertNull(Code128Encoder.encodeOrNull(""))
        assertNull(Code128Encoder.encodeOrNull("CÓDIGO"))
        assertNull(Code128Encoder.encodeOrNull("7891000100103\n"))
    }

    @Test
    fun `encoding is deterministic and differs between values`() {
        assertEquals(Code128Encoder.encodeOrNull("LOTE-1"), Code128Encoder.encodeOrNull("LOTE-1"))
        assertNotEquals(Code128Encoder.encodeOrNull("LOTE-1"), Code128Encoder.encodeOrNull("LOTE-2"))
    }
}
