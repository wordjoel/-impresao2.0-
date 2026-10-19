package br.com.atos.printbridge.domain.model.label

/**
 * Modelos de etiqueta nativos ("semente"), no mesmo espírito dos layouts padrão do PesoHub
 * (`LayoutsPadraoPanel`) — porém simples e próprios do destino, sem importar os layouts de fábrica
 * da balança Ramuza (ver PESOHUB-LABEL-MODULE-AUDIT.md §3, "DO NOT IMPORT").
 *
 * Ids determinísticos: semear duas vezes não duplica (o repositório usa REPLACE por id).
 */
object BuiltInLabelTemplates {

    const val PRICE_TEMPLATE_ID = "builtin-40x30-preco"
    const val SHIPPING_TEMPLATE_ID = "builtin-60x40-expedicao"
    const val QR_TEMPLATE_ID = "builtin-30x20-qr"

    fun all(): List<LabelTemplate> = listOf(price40x30(), shipping60x40(), qr30x20())

    private fun price40x30() = LabelTemplate(
        id = PRICE_TEMPLATE_ID,
        name = "Etiqueta 40x30 — Preço",
        widthMm = 40,
        heightMm = 30,
        elements = listOf(
            box(PRICE_TEMPLATE_ID, 1, x = 0.5f, y = 0.5f, w = 39f, h = 29f),
            text(PRICE_TEMPLATE_ID, 2, LabelField.PRODUTO, x = 1f, y = 1.5f, w = 38f, h = 4.5f, font = 2.4f, align = LabelAlign.CENTER),
            line(PRICE_TEMPLATE_ID, 3, x = 1f, y = 6.5f, w = 38f),
            text(PRICE_TEMPLATE_ID, 4, LabelField.PRECO, x = 1.5f, y = 8f, w = 21f, h = 9f, font = 8f),
            text(PRICE_TEMPLATE_ID, 5, LabelField.PRECO_KG, x = 23f, y = 9f, w = 15.5f, h = 4f, font = 2.4f),
            text(PRICE_TEMPLATE_ID, 6, LabelField.DATA, x = 23f, y = 13.5f, w = 15.5f, h = 3.5f, font = 2.2f, align = LabelAlign.RIGHT),
            barcode(PRICE_TEMPLATE_ID, 7, x = 1.5f, y = 20f, w = 37f, h = 9f),
        ),
        isBuiltIn = true,
        isDefault = true,
        createdAt = SEED_MILLIS,
        updatedAt = SEED_MILLIS,
    )

    private fun shipping60x40() = LabelTemplate(
        id = SHIPPING_TEMPLATE_ID,
        name = "Etiqueta 60x40 — Expedição",
        widthMm = 60,
        heightMm = 40,
        elements = listOf(
            box(SHIPPING_TEMPLATE_ID, 1, x = 0.5f, y = 0.5f, w = 59f, h = 39f),
            text(SHIPPING_TEMPLATE_ID, 2, LabelField.PRODUTO, x = 2f, y = 2f, w = 56f, h = 6f, font = 3.5f, align = LabelAlign.CENTER),
            line(SHIPPING_TEMPLATE_ID, 3, x = 2f, y = 9f, w = 56f),
            text(SHIPPING_TEMPLATE_ID, 4, LabelField.PESO, x = 3f, y = 11f, w = 26f, h = 5f, font = 3f),
            text(SHIPPING_TEMPLATE_ID, 5, LabelField.TARA, x = 31f, y = 11f, w = 26f, h = 5f, font = 3f),
            text(SHIPPING_TEMPLATE_ID, 6, LabelField.LOTE, x = 3f, y = 17f, w = 26f, h = 5f, font = 3f),
            text(SHIPPING_TEMPLATE_ID, 7, LabelField.VALIDADE, x = 31f, y = 17f, w = 26f, h = 5f, font = 3f),
            text(SHIPPING_TEMPLATE_ID, 8, LabelField.LOJA, x = 3f, y = 23f, w = 26f, h = 5f, font = 3f),
            barcode(SHIPPING_TEMPLATE_ID, 9, x = 3f, y = 29f, w = 54f, h = 9f, barcodeType = BarcodeType.CODE128),
        ),
        isBuiltIn = true,
        createdAt = SEED_MILLIS,
        updatedAt = SEED_MILLIS,
    )

    private fun qr30x20() = LabelTemplate(
        id = QR_TEMPLATE_ID,
        name = "Etiqueta 30x20 — QR",
        widthMm = 30,
        heightMm = 20,
        elements = listOf(
            qr(QR_TEMPLATE_ID, 1, x = 2f, y = 2f, w = 16f, h = 16f),
            text(QR_TEMPLATE_ID, 2, LabelField.LOJA, x = 19f, y = 3f, w = 10f, h = 4f, font = 2.2f),
            text(QR_TEMPLATE_ID, 3, LabelField.DATA, x = 19f, y = 8f, w = 10f, h = 4f, font = 2.2f),
            text(QR_TEMPLATE_ID, 4, LabelField.CODIGO, x = 19f, y = 13f, w = 10f, h = 4f, font = 2f),
        ),
        isBuiltIn = true,
        createdAt = SEED_MILLIS,
        updatedAt = SEED_MILLIS,
    )

    private const val SEED_MILLIS = 1_700_000_000_000L

    private fun id(templateId: String, index: Int) = "$templateId-e$index"

    private fun box(templateId: String, index: Int, x: Float, y: Float, w: Float, h: Float) = LabelElement(
        id = id(templateId, index),
        type = LabelElementType.CAIXA,
        xMm = x, yMm = y, widthMm = w, heightMm = h,
        thicknessMm = 0.3f,
    )

    private fun line(templateId: String, index: Int, x: Float, y: Float, w: Float) = LabelElement(
        id = id(templateId, index),
        type = LabelElementType.LINHA,
        xMm = x, yMm = y, widthMm = w, heightMm = 0.4f,
        thicknessMm = 0.4f,
    )

    private fun text(
        templateId: String,
        index: Int,
        field: LabelField,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        font: Float,
        align: LabelAlign = LabelAlign.LEFT,
    ) = LabelElement(
        id = id(templateId, index),
        type = LabelElementType.CAMPO,
        xMm = x, yMm = y, widthMm = w, heightMm = h,
        field = field,
        fontSizeMm = font,
        align = align,
    )

    private fun barcode(
        templateId: String,
        index: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        barcodeType: BarcodeType = BarcodeType.EAN13,
    ) = LabelElement(
        id = id(templateId, index),
        type = LabelElementType.CODIGO_BARRAS,
        xMm = x, yMm = y, widthMm = w, heightMm = h,
        field = LabelField.CODIGO,
        barcodeType = barcodeType,
        fontSizeMm = 3f,
    )

    private fun qr(templateId: String, index: Int, x: Float, y: Float, w: Float, h: Float) = LabelElement(
        id = id(templateId, index),
        type = LabelElementType.QRCODE,
        xMm = x, yMm = y, widthMm = w, heightMm = h,
        field = LabelField.CODIGO,
    )
}
