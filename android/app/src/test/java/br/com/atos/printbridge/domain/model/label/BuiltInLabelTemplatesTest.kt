package br.com.atos.printbridge.domain.model.label

import br.com.atos.printbridge.domain.model.label.barcode.BarcodeEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os modelos nativos são o primeiro contato do operador com o módulo (semeados quando a tabela está
 * vazia), e são escritos à mão — por isso valem teste: um elemento fora dos limites da etiqueta só
 * apareceria como um erro de validação na cara do operador na primeira edição.
 */
class BuiltInLabelTemplatesTest {

    private val templates = BuiltInLabelTemplates.all()

    @Test
    fun `three built in templates with deterministic unique ids`() {
        assertEquals(3, templates.size)
        assertEquals(3, templates.map { it.id }.toSet().size)
        assertEquals(templates.map { it.id }, BuiltInLabelTemplates.all().map { it.id })
    }

    @Test
    fun `exactly one built in template is the default`() {
        assertEquals(1, templates.count { it.isDefault })
        assertEquals(BuiltInLabelTemplates.PRICE_TEMPLATE_ID, templates.single { it.isDefault }.id)
        assertTrue(templates.all { it.isBuiltIn })
    }

    @Test
    fun `every element fits inside its label`() {
        templates.forEach { template ->
            template.elements.forEach { element ->
                assertTrue(
                    "${template.id}/${element.id} começa fora da etiqueta",
                    element.xMm >= 0f && element.yMm >= 0f,
                )
                assertTrue(
                    "${template.id}/${element.id} passa da largura de ${template.widthMm}mm",
                    element.xMm + element.widthMm <= template.widthMm + 0.5f,
                )
                assertTrue(
                    "${template.id}/${element.id} passa da altura de ${template.heightMm}mm",
                    element.yMm + element.heightMm <= template.heightMm + 0.5f,
                )
                assertTrue("${template.id}/${element.id} tem dimensão inválida", element.widthMm > 0f && element.heightMm > 0f)
            }
        }
    }

    @Test
    fun `element ids are unique inside each template`() {
        templates.forEach { template ->
            assertEquals(template.id, template.elements.size, template.elements.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `data elements always carry a variable field`() {
        templates.flatMap { it.elements }
            .filter {
                it.type == LabelElementType.CAMPO ||
                    it.type == LabelElementType.CODIGO_BARRAS ||
                    it.type == LabelElementType.QRCODE
            }
            .forEach { assertTrue("${it.id} sem campo variável", it.field != null) }
    }

    @Test
    fun `every barcode in the built in templates can be drawn by the preview encoder`() {
        templates.flatMap { it.elements }
            .filter { it.type == LabelElementType.CODIGO_BARRAS }
            .forEach { element ->
                val value = element.field?.sample ?: element.text
                assertTrue(
                    "${element.id}: '${value}' não é codificável como ${element.barcodeType}",
                    BarcodeEncoder.encode(element.barcodeType, value).isNotEmpty(),
                )
            }
    }
}
