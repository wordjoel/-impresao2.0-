package br.com.atos.printbridge.domain.model

data class Printer(
    val id: String,
    val storeId: String?,
    val name: String,
    val model: PrinterModel,
    val ipAddress: String,
    val port: Int,
    val protocol: PrinterProtocol,
    val language: PrinterLanguage,
    val isDefault: Boolean,
    val enabled: Boolean,
    val lastSeenAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Resolução da cabeça (203 ou 300 na linha TSC) — define a nitidez da rasterização de PDF/imagem. */
    val dpi: Int = DEFAULT_DPI,
    /** Papel padrão do perfil; PDF/imagem são encaixados nesta área. */
    val labelWidthMm: Int = DEFAULT_LABEL_WIDTH_MM,
    val labelHeightMm: Int = DEFAULT_LABEL_HEIGHT_MM,
    // v8 — identidade e capacidades DESCOBERTAS (IPP/SNMP/mDNS). Nulo = a impressora não informou.
    val manufacturer: String? = null,
    val modelName: String? = null,
    val hostname: String? = null,
    /** Caminho do serviço IPP (`ipp/print`), só para [PrinterProtocol.IPP]. */
    val ippPath: String? = null,
    val capabilities: PrinterCapabilities? = null,
) {
    /** Etiqueta térmica por socket (ZPL/TSPL) — o perfil de mídia em mm só vale para estas. */
    val isLabelPrinter: Boolean get() = protocol == PrinterProtocol.TCP

    /** Fabricante para exibição: o descoberto; senão, o implícito na escolha manual de modelo TSC. */
    val displayManufacturer: String?
        get() = manufacturer ?: when (model) {
            PrinterModel.TSC_ML241, PrinterModel.TSC_MB241, PrinterModel.OTHER_TSC -> "TSC"
            else -> null
        }

    /** Modelo para exibição: o descoberto; senão, o escolhido no cadastro; senão, nada. */
    val displayModel: String?
        get() = modelName ?: model.takeIf { it != PrinterModel.UNKNOWN }?.label

    companion object {
        const val DEFAULT_DPI = 203
        const val DEFAULT_LABEL_WIDTH_MM = 100
        const val DEFAULT_LABEL_HEIGHT_MM = 72
        val SUPPORTED_DPI = listOf(203, 300)
        const val NOT_INFORMED = "Não informado"
    }
}
