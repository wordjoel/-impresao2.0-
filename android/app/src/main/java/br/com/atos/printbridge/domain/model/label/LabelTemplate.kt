package br.com.atos.printbridge.domain.model.label

/**
 * Uma etiqueta salva (o "Formato de Impressão" do PesoHub). Dimensões e posições em milímetros.
 *
 * O `dpi` é do equipamento de destino (TSC ML241/MB241 usam 203 dpi; alguns modelos, 300) e só é
 * usado na conversão mm→dots do ZPL, nunca na edição do layout.
 */
data class LabelTemplate(
    val id: String,
    val name: String,
    val widthMm: Int,
    val heightMm: Int,
    val dpi: Int = DEFAULT_DPI,
    val elements: List<LabelElement> = emptyList(),
    val isBuiltIn: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val dotsPerMm: Float get() = dpi / 25.4f

    fun dots(mm: Number): Int = Math.round(mm.toFloat() * dotsPerMm)

    companion object {
        const val DEFAULT_DPI = 203
        val AVAILABLE_DPIS = listOf(203, 300)
        const val MIN_MM = 10
        const val MAX_MM = 220
        const val MAX_NAME_LENGTH = 60
        const val MAX_TEXT_LENGTH = 200

        fun isValidWidth(mm: Int) = mm in MIN_MM..MAX_MM
        fun isValidHeight(mm: Int) = mm in MIN_MM..MAX_MM
        fun isValidDpi(dpi: Int) = dpi in AVAILABLE_DPIS
    }
}
