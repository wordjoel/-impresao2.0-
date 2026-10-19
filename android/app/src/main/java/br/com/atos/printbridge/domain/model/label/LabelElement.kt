package br.com.atos.printbridge.domain.model.label

/** Alinhamento horizontal do conteúdo dentro da caixa do elemento. */
enum class LabelAlign(val label: String) {
    LEFT("Esquerda"),
    CENTER("Centro"),
    RIGHT("Direita"),
}

/** Simbologia do código de barras. O PesoHub usava apenas EAN13 (JsBarcode); Code 128 foi
 * adicionado porque a TSC o suporta nativamente (`^BC`) e é o formato típico de etiqueta de
 * logística/expedição. */
enum class BarcodeType(val label: String) {
    EAN13("EAN-13"),
    CODE128("Code 128"),
}

/**
 * Um elemento posicionado da etiqueta. Todas as medidas são em **milímetros** — mesma decisão de
 * domínio do PesoHub (`FormatoImpressaoElementoPayload`), para que o layout seja independente da
 * resolução da impressora (203/300 dpi) e da escala da tela.
 *
 * O tipo "campo" e "texto" compartilham [text]; "campo" ignora [text] e usa [field]; "código de
 * barras"/"QR" usam [field] quando definido (dado variável) ou [text] como dado literal fixo.
 */
data class LabelElement(
    val id: String,
    val type: LabelElementType,
    val xMm: Float,
    val yMm: Float,
    val widthMm: Float,
    val heightMm: Float,
    val text: String = "",
    val field: LabelField? = null,
    val barcodeType: BarcodeType = BarcodeType.CODE128,
    val rotationDeg: Int = 0,
    val align: LabelAlign = LabelAlign.LEFT,
    val fontSizeMm: Float = 3f,
    val thicknessMm: Float = 0.5f,
) {
    /** Valor que este elemento imprime, dado um conjunto de dados variáveis. */
    fun resolveValue(data: LabelData): String = when (type) {
        LabelElementType.CAMPO -> field?.let { data.resolve(it) } ?: field?.sample ?: ""
        LabelElementType.CODIGO_BARRAS, LabelElementType.QRCODE ->
            field?.let { data.resolve(it) } ?: text
        else -> text
    }

    companion object {
        const val MIN_ROTATION = 0
        val ROTATIONS = listOf(0, 90, 180, 270)
        const val MIN_FONT_MM = 1.5f
        const val MAX_FONT_MM = 20f
        const val MIN_THICKNESS_MM = 0.2f
        const val MAX_THICKNESS_MM = 2.0f
    }
}

enum class LabelElementType(val label: String, val defaultWidthMm: Float, val defaultHeightMm: Float) {
    TEXTO("Texto", 30f, 5f),
    CAMPO("Campo", 30f, 5f),
    CODIGO_BARRAS("Código de barras", 30f, 12f),
    QRCODE("QR Code", 15f, 15f),
    IMAGEM("Imagem", 20f, 20f),
    LINHA("Linha", 30f, 0.5f),
    CAIXA("Caixa/Moldura", 30f, 20f),
}
