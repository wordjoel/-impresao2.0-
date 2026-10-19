package br.com.atos.printbridge.domain.ipp

import br.com.atos.printbridge.domain.model.PrinterCapabilities

/**
 * O que a impressora disse de si mesma no Get-Printer-Attributes. Tudo nulo/vazio quando ela não
 * informa — nada aqui é presumido.
 */
data class IppPrinterDescription(
    val manufacturer: String?,
    val model: String?,
    val makeAndModel: String?,
    val name: String?,
    val info: String?,
    val location: String?,
    /** 3 = idle, 4 = processing, 5 = stopped (RFC 8011 §5.4.11). */
    val state: Int?,
    val stateReasons: List<String>,
    val acceptingJobs: Boolean?,
    val capabilities: PrinterCapabilities,
) {
    val stateLabel: String
        get() = when (state) {
            3 -> "Ociosa"
            4 -> "Imprimindo"
            5 -> "Parada"
            null -> "Não informado"
            else -> "Estado $state"
        }

    /** Motivos relevantes (sem o "none" que significa tudo certo). */
    val problems: List<String> get() = stateReasons.filter { it != "none" }

    companion object {
        /** "all" traz descrição + job-template; media-col-database fica de fora por definição. */
        val REQUESTED_ATTRIBUTES = listOf("all")

        fun from(response: IppCodec.Response): IppPrinterDescription {
            val makeAndModel = response.string("printer-make-and-model")
            val deviceId = parseDeviceId(response.string("printer-device-id"))
            val manufacturer = deviceId["MFG"] ?: deviceId["MANUFACTURER"]
            val model = deviceId["MDL"] ?: deviceId["MODEL"]
            val pwgRes = response.resolutions("pwg-raster-document-resolution-supported").map { it.dpi }
            return IppPrinterDescription(
                manufacturer = manufacturer,
                model = model ?: makeAndModel?.let { mm ->
                    // Sem device-id, o modelo é o make-and-model sem o fabricante já conhecido.
                    manufacturer?.let { mm.removePrefix(it).trim().ifEmpty { null } } ?: mm
                },
                makeAndModel = makeAndModel,
                name = response.string("printer-name"),
                info = response.string("printer-info"),
                location = response.string("printer-location"),
                state = response.int("printer-state"),
                stateReasons = response.strings("printer-state-reasons"),
                acceptingJobs = response.values("printer-is-accepting-jobs").filterIsInstance<Boolean>().firstOrNull(),
                capabilities = PrinterCapabilities(
                    documentFormats = response.strings("document-format-supported").distinct(),
                    resolutionsDpi = response.resolutions("printer-resolution-supported").map { it.dpi }.distinct().sorted(),
                    defaultResolutionDpi = response.resolutions("printer-resolution-default").firstOrNull()?.dpi,
                    media = response.strings("media-supported").distinct(),
                    defaultMedia = response.string("media-default"),
                    mediaSources = response.strings("media-source-supported").distinct(),
                    mediaTypes = response.strings("media-type-supported").distinct(),
                    sides = response.strings("sides-supported").distinct(),
                    colorModes = response.strings("print-color-mode-supported").distinct(),
                    pwgRasterTypes = response.strings("pwg-raster-document-type-supported").distinct(),
                    pwgRasterResolutionsDpi = pwgRes.distinct().sorted(),
                ),
            )
        }

        /** IEEE 1284 device ID: `MFG:HP;MDL:LaserJet ...;CMD:PCL,PJL;` → mapa por chave. */
        fun parseDeviceId(deviceId: String?): Map<String, String> =
            deviceId.orEmpty().split(';').mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim()
            }.filter { it.second.isNotEmpty() }.toMap()
    }
}
