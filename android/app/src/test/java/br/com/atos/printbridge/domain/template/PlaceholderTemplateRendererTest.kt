package br.com.atos.printbridge.domain.template

import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.model.PrinterLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderTemplateRendererTest {

    /** Dados de exemplo da seção 7 do prompt mestre, com os códigos preenchidos para exercitar o ^BC/^BQ. */
    private val sifData = mapOf(
        "loteSequencia" to "96644",
        "pesoLiquido" to "10,020 Kg",
        "pesoBruto" to "10,390 Kg",
        "codigoBarra1" to "7891234567895",
        "codigoBarra2" to "0102030405",
        "codigoBarra3" to "",
        "codigoBarra4" to "",
        "qrCodePayload" to "https://athos.example/lote/96644",
    )

    @Test
    fun `SIF reference label renders to the exact expected ZPL`() {
        val zpl = PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, sifData)

        val expected = listOf(
            "^XA",
            "^MMT",
            "^PW812",
            "^LL576",
            "^LS0",
            "^FO30,30^BY2,2,60",
            "^BCN,60,Y,N,N",
            "^FD7891234567895^FS",
            "^FO30,110^BY2,2,60",
            "^BCN,60,Y,N,N",
            "^FD0102030405^FS",
            "^FO30,190^BY2,2,60",
            "^BCN,60,Y,N,N",
            "^FD^FS",
            "^FO30,270^BY2,2,60",
            "^BCN,60,Y,N,N",
            "^FD^FS",
            "^FO560,30^BQN,2,6",
            "^FDQA,https://athos.example/lote/96644^FS",
            "^FO400,200^GB60,60,3^FS",
            "^FO480,190^A0N,28,28^FDPeso Líquido^FS",
            "^FO480,225^A0N,36,36^FD10,020 Kg^FS",
            "^FO480,270^A0N,28,28^FDPeso Bruto^FS",
            "^FO480,305^A0N,36,36^FD10,390 Kg^FS",
            "^FO680,500^A0N,24,24^FDSequ. 96644^FS",
            "^XZ",
        ).joinToString("\n")
        assertEquals(expected, zpl)
        assertFalse(zpl.contains("{{"))
    }

    @Test
    fun `SIF template declares all eight fields of the reference label`() {
        assertEquals(sifData.keys, BuiltInPrintTemplates.SIF_PESAGEM_100X72.variables.toSet())
    }

    @Test
    fun `missing variable fails listing every absent field and nothing is rendered`() {
        val partial = sifData - "pesoBruto" - "loteSequencia"

        val ex = assertThrows(MissingTemplateVariablesException::class.java) {
            PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, partial)
        }

        assertEquals(listOf("pesoBruto", "loteSequencia"), ex.missing)
    }

    @Test
    fun `ZPL command characters in a value are rejected instead of injected`() {
        val ex = assertThrows(InvalidTemplateValueException::class.java) {
            PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, sifData + ("pesoLiquido" to "10^XZ"))
        }
        assertEquals("pesoLiquido", ex.variable)
    }

    @Test
    fun `line breaks in a value are rejected`() {
        assertThrows(InvalidTemplateValueException::class.java) {
            PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, sifData + ("loteSequencia" to "1\n2"))
        }
    }

    @Test
    fun `TSPL template rejects quotes but accepts ZPL carets as plain data`() {
        val tspl = PrintTemplate("t", "t", PrinterLanguage.TSPL, "TEXT 10,10,\"3\",0,1,1,\"{{nome}}\"\nPRINT 1")

        assertEquals(
            "TEXT 10,10,\"3\",0,1,1,\"A^B\"\nPRINT 1",
            PlaceholderTemplateRenderer.render(tspl, mapOf("nome" to "A^B")),
        )
        assertThrows(InvalidTemplateValueException::class.java) {
            PlaceholderTemplateRenderer.render(tspl, mapOf("nome" to "x\" PRINT 99"))
        }
    }

    @Test
    fun `extra variables are ignored and whitespace inside braces is tolerated`() {
        val template = PrintTemplate("t", "t", PrinterLanguage.ZPL, "^XA^FD{{ a }}-{{b}}^FS^XZ")

        val out = PlaceholderTemplateRenderer.render(template, mapOf("a" to "1", "b" to "2", "unused" to "x"))

        assertEquals("^XA^FD1-2^FS^XZ", out)
        assertTrue(template.variables == listOf("a", "b"))
    }

    @Test
    fun `lookup by id finds only built in templates`() {
        assertEquals(BuiltInPrintTemplates.SIF_PESAGEM_100X72, BuiltInPrintTemplates.byId("sif-pesagem-100x72"))
        assertEquals(null, BuiltInPrintTemplates.byId("preco-40x30"))
    }
}
