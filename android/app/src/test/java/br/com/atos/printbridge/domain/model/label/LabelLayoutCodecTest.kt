package br.com.atos.printbridge.domain.model.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O codec é o único ponto de serialização do layout (coluna `layout TEXT`), então ele precisa
 * sobreviver a textos com TAB/quebra de linha/barra invertida sem corromper a leitura — um
 * elemento perdido aqui é um elemento que some da etiqueta impressa.
 */
class LabelLayoutCodecTest {

    private fun element(
        id: String = "e1",
        type: LabelElementType = LabelElementType.TEXTO,
        field: LabelField? = null,
        text: String = "",
        barcodeType: BarcodeType = BarcodeType.CODE128,
    ) = LabelElement(
        id = id,
        type = type,
        xMm = 1.5f,
        yMm = 2.25f,
        widthMm = 30f,
        heightMm = 5f,
        text = text,
        field = field,
        barcodeType = barcodeType,
        rotationDeg = 90,
        align = LabelAlign.RIGHT,
        fontSizeMm = 4.5f,
        thicknessMm = 0.7f,
    )

    @Test
    fun `round trip preserves every element field`() {
        val elements = listOf(
            element(id = "e1", text = "simples"),
            element(id = "e2", type = LabelElementType.CAMPO, field = LabelField.PRECO_KG),
            element(id = "e3", type = LabelElementType.CODIGO_BARRAS, field = LabelField.CODIGO, barcodeType = BarcodeType.EAN13),
            element(id = "e4", type = LabelElementType.QRCODE, field = LabelField.LOJA),
            element(id = "e5", type = LabelElementType.IMAGEM),
            element(id = "e6", type = LabelElementType.CAIXA),
        )

        val decoded = LabelLayoutCodec.decode(LabelLayoutCodec.encode(elements))

        assertEquals(elements, decoded)
    }

    @Test
    fun `text with tab newline and backslash survives the round trip`() {
        val text = "A\tB\\C\nD\rE"
        val elements = listOf(element(text = text))

        val decoded = LabelLayoutCodec.decode(LabelLayoutCodec.encode(elements))

        assertEquals(listOf(text), decoded.map { it.text })
    }

    @Test
    fun `null raw or blank raw decodes to empty list`() {
        assertEquals(emptyList<LabelElement>(), LabelLayoutCodec.decode(null))
        assertEquals(emptyList<LabelElement>(), LabelLayoutCodec.decode(""))
        assertEquals(emptyList<LabelElement>(), LabelLayoutCodec.decode("   "))
    }

    @Test
    fun `malformed lines are ignored instead of failing the whole layout`() {
        val valid = LabelLayoutCodec.encode(listOf(element(id = "ok", text = "válido")))
        val raw = "lixo\n$valid\nnao-tem-colunas\n\t\t\t"

        val decoded = LabelLayoutCodec.decode(raw)

        assertEquals(listOf("ok"), decoded.map { it.id })
        assertEquals("válido", decoded.single().text)
    }

    @Test
    fun `unknown element type is dropped`() {
        val raw = "e1\tNAO_EXISTE\t0\t0\t10\t5\t\t\tCODE128\t0\tLEFT\t3\t0.5"

        assertTrue(LabelLayoutCodec.decode(raw).isEmpty())
    }

    @Test
    fun `field null is preserved and produces no field value`() {
        val decoded = LabelLayoutCodec.decode(LabelLayoutCodec.encode(listOf(element(field = null))))

        assertEquals(null, decoded.single().field)
    }

    @Test
    fun `decode caps the number of records`() {
        val many = (1..600).map { element(id = "e$it", text = "t$it") }

        val decoded = LabelLayoutCodec.decode(LabelLayoutCodec.encode(many))

        assertEquals(500, decoded.size)
    }
}
