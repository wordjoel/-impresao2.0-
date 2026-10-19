package br.com.atos.printbridge.printing.label

import br.com.atos.printbridge.domain.model.label.BarcodeType
import br.com.atos.printbridge.domain.model.label.LabelAlign
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelField
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O renderer é a fronteira entre o layout em mm e o ZPL que a TSC recebe. Os testes cobrem a
 * conversão mm→dots (a mesma etiqueta precisa servir para 203 e 300 dpi), o comando escolhido por
 * tipo de elemento e o saneamento de texto — o `^` e o `~` do ZPL são "injeção de comando" numa
 * etiqueta vinda de dados do operador.
 */
class ZplLabelRendererTest {

    private val renderer = ZplLabelRenderer()

    private fun template(
        widthMm: Int = 40,
        heightMm: Int = 30,
        dpi: Int = 203,
        elements: List<LabelElement> = emptyList(),
    ) = LabelTemplate(
        id = "t1",
        name = "Teste",
        widthMm = widthMm,
        heightMm = heightMm,
        dpi = dpi,
        elements = elements,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun element(
        type: LabelElementType,
        xMm: Float = 0f,
        yMm: Float = 0f,
        widthMm: Float = 20f,
        heightMm: Float = 5f,
        text: String = "",
        field: LabelField? = null,
        barcodeType: BarcodeType = BarcodeType.CODE128,
        rotationDeg: Int = 0,
        align: LabelAlign = LabelAlign.LEFT,
        fontSizeMm: Float = 3f,
        thicknessMm: Float = 0.3f,
    ) = LabelElement(
        id = "e-${type.name}-${xMm}-${yMm}",
        type = type,
        xMm = xMm,
        yMm = yMm,
        widthMm = widthMm,
        heightMm = heightMm,
        text = text,
        field = field,
        barcodeType = barcodeType,
        rotationDeg = rotationDeg,
        align = align,
        fontSizeMm = fontSizeMm,
        thicknessMm = thicknessMm,
    )

    @Test
    fun `label dimensions are converted from mm to dots at 203 dpi`() {
        val zpl = renderer.render(template(), LabelData())

        assertTrue(zpl.startsWith("^XA"))
        assertTrue(zpl.endsWith("^XZ"))
        assertTrue(zpl.contains("^PW320\n"))
        assertTrue(zpl.contains("^LL240\n"))
        assertTrue(zpl.contains("^LH0,0\n"))
    }

    @Test
    fun `the same layout renders differently at 300 dpi`() {
        val zpl = renderer.render(template(dpi = 300), LabelData())

        assertTrue(zpl.contains("^PW472\n"))
        assertTrue(zpl.contains("^LL354\n"))
    }

    @Test
    fun `campo uses the value from the label data`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CAMPO, field = LabelField.PRODUTO))),
            LabelData(mapOf(LabelField.PRODUTO to "CAFE TORRADO")),
        )

        assertTrue(zpl.contains("^FDCAFE TORRADO^FS"))
    }

    @Test
    fun `campo falls back to the sample value when the data does not provide it`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CAMPO, field = LabelField.PRODUTO))),
            LabelData(),
        )

        assertTrue(zpl.contains("^FD${LabelField.PRODUTO.sample}^FS"))
    }

    @Test
    fun `coordinates are converted with the template dpi`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, xMm = 10f, yMm = 5f, text = "X"))),
            LabelData(),
        )

        // 10mm e 5mm a 203 dpi: 80 e 40 dots.
        assertTrue(zpl.contains("^FO80,40"))
    }

    @Test
    fun `text is transliterated and zpl control characters are neutralized`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, text = "PÃO^X~Y"))),
            LabelData(),
        )

        assertTrue(zpl.contains("^FDPAO X Y^FS"))
    }

    @Test
    fun `blank text renders no field command at all`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, text = "   "))),
            LabelData(),
        )

        assertFalse(zpl.contains("^FD"))
        assertEquals("^XA\n^PW320\n^LL240\n^LH0,0\n^XZ", zpl)
    }

    @Test
    fun `rotation maps to the zpl orientation character`() {
        val field = LabelField.PRODUTO
        val rotated90 = renderer.render(
            template(elements = listOf(element(LabelElementType.CAMPO, field = field, rotationDeg = 90))),
            LabelData(),
        )
        val rotated180 = renderer.render(
            template(elements = listOf(element(LabelElementType.CAMPO, field = field, rotationDeg = 180))),
            LabelData(),
        )

        assertTrue(rotated90.contains("^A0R,"))
        assertTrue(rotated180.contains("^A0I,"))
    }

    @Test
    fun `alignment is forwarded to the field block`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, text = "X", align = LabelAlign.CENTER))),
            LabelData(),
        )

        assertTrue(zpl.contains(",C,,"))
    }

    @Test
    fun `compatible ean13 data uses the be command`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CODIGO_BARRAS, field = LabelField.CODIGO, barcodeType = BarcodeType.EAN13))),
            LabelData(),
        )

        assertTrue(zpl.contains("^BE"))
        assertTrue(zpl.contains("^FD${LabelField.CODIGO.sample}^FS"))
    }

    @Test
    fun `ean13 with incompatible data falls back to plain text instead of an invalid symbol`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CODIGO_BARRAS, field = LabelField.CODIGO, barcodeType = BarcodeType.EAN13))),
            LabelData(mapOf(LabelField.CODIGO to "ABC")),
        )

        assertFalse(zpl.contains("^BE"))
        assertTrue(zpl.contains("^FDABC^FS"))
    }

    @Test
    fun `code128 uses the bc command`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CODIGO_BARRAS, field = LabelField.LOTE, barcodeType = BarcodeType.CODE128))),
            LabelData(),
        )

        assertTrue(zpl.contains("^BC"))
        assertFalse(zpl.contains("^BE"))
    }

    @Test
    fun `barcode with blank literal value renders nothing`() {
        // Campo nulo + texto vazio: `LabelData.resolve` nunca devolve vazio para um campo conhecido
        // (cai no valor de exemplo), então o caminho "sem dado nenhum" é o do texto literal.
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CODIGO_BARRAS, field = null, text = "   "))),
            LabelData(),
        )

        assertFalse(zpl.contains("^BE"))
        assertFalse(zpl.contains("^BC"))
        assertFalse(zpl.contains("^FD"))
    }

    @Test
    fun `qr code uses the bq command with the detected field`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.QRCODE, field = LabelField.CODIGO, heightMm = 15f))),
            LabelData(),
        )

        assertTrue(zpl.contains("^BQN,2,"))
        assertTrue(zpl.contains("^FDQA,${LabelField.CODIGO.sample}^FS"))
    }

    @Test
    fun `box and line use gb with converted width height and thickness`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.CAIXA, widthMm = 39f, heightMm = 29f, thicknessMm = 0.3f))),
            LabelData(),
        )

        assertTrue(zpl.contains("^GB312,232,2^FS"))
    }

    @Test
    fun `image element renders a placeholder frame and never a raster command`() {
        val zpl = renderer.render(
            template(elements = listOf(element(LabelElementType.IMAGEM, widthMm = 20f, heightMm = 20f))),
            LabelData(),
        )

        assertTrue(zpl.contains("^GB"))
        assertFalse(zpl.contains("^GFA"))
    }

    @Test
    fun `font size is converted and has a safe minimum`() {
        val tiny = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, text = "X", fontSizeMm = 0.1f))),
            LabelData(),
        )
        val normal = renderer.render(
            template(elements = listOf(element(LabelElementType.TEXTO, text = "X", fontSizeMm = 3f))),
            LabelData(),
        )

        assertTrue(tiny.contains("^A0N,8"))
        assertTrue(normal.contains("^A0N,24"))
    }

    @Test
    fun `sanitize is exposed and strips line breaks`() {
        assertEquals("A B", ZplLabelRenderer.sanitize("A\nB"))
        assertEquals("OP 01", ZplLabelRenderer.sanitize("OP~01"))
    }
}
