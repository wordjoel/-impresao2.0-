package br.com.atos.printbridge.domain.model

/**
 * Capacidades que a PRÓPRIA impressora declarou (hoje: resposta IPP Get-Printer-Attributes).
 * Lista vazia/nulo = a impressora não informou — a interface mostra "Não informado", nunca um
 * valor presumido. Mídias e bandejas ficam nos nomes PWG 5101.1 que a impressora usa.
 */
data class PrinterCapabilities(
    val documentFormats: List<String> = emptyList(),
    val resolutionsDpi: List<Int> = emptyList(),
    val defaultResolutionDpi: Int? = null,
    val media: List<String> = emptyList(),
    val defaultMedia: String? = null,
    val mediaSources: List<String> = emptyList(),
    val mediaTypes: List<String> = emptyList(),
    val sides: List<String> = emptyList(),
    val colorModes: List<String> = emptyList(),
    val pwgRasterTypes: List<String> = emptyList(),
    val pwgRasterResolutionsDpi: List<Int> = emptyList(),
) {
    val supportsPdf: Boolean get() = PDF in documentFormats
    val supportsPwgRaster: Boolean get() = PWG_RASTER in documentFormats && pwgRasterTypes.any { it in SUPPORTED_PWG_TYPES }
    val supportsDuplex: Boolean get() = sides.any { it.startsWith("two-sided") }
    val isColor: Boolean get() = colorModes.any { it == "color" || it == "auto" && colorModes.contains("color") }

    /** Serialização simples para a coluna Room (valores PWG/MIME não têm quebra de linha nem '|'). */
    fun encode(): String = listOf(
        "formats" to documentFormats.joinToString("|"),
        "res" to resolutionsDpi.joinToString("|"),
        "resDefault" to (defaultResolutionDpi?.toString() ?: ""),
        "media" to media.joinToString("|"),
        "mediaDefault" to (defaultMedia ?: ""),
        "sources" to mediaSources.joinToString("|"),
        "types" to mediaTypes.joinToString("|"),
        "sides" to sides.joinToString("|"),
        "color" to colorModes.joinToString("|"),
        "pwgTypes" to pwgRasterTypes.joinToString("|"),
        "pwgRes" to pwgRasterResolutionsDpi.joinToString("|"),
    ).joinToString("\n") { (k, v) -> "$k=$v" }

    companion object {
        const val PDF = "application/pdf"
        const val PWG_RASTER = "image/pwg-raster"

        /** Tipos de PWG Raster que o encoder do Print Bridge gera (ver PwgRasterEncoder). */
        val SUPPORTED_PWG_TYPES = listOf("black_1", "sgray_8")

        fun decode(text: String?): PrinterCapabilities? {
            if (text.isNullOrBlank()) return null
            val map = text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            fun list(key: String) = map[key].orEmpty().split('|').filter { it.isNotBlank() }
            fun ints(key: String) = list(key).mapNotNull { it.toIntOrNull() }
            return PrinterCapabilities(
                documentFormats = list("formats"),
                resolutionsDpi = ints("res"),
                defaultResolutionDpi = map["resDefault"]?.toIntOrNull(),
                media = list("media"),
                defaultMedia = map["mediaDefault"]?.takeIf { it.isNotBlank() },
                mediaSources = list("sources"),
                mediaTypes = list("types"),
                sides = list("sides"),
                colorModes = list("color"),
                pwgRasterTypes = list("pwgTypes"),
                pwgRasterResolutionsDpi = ints("pwgRes"),
            )
        }
    }
}

/**
 * Tamanho de mídia a partir do nome PWG autodescritivo (`iso_a4_210x297mm`, `na_letter_8.5x11in`).
 * O tamanho vem do PRÓPRIO nome que a impressora anunciou — não há tabela de papéis presumidos.
 */
data class PwgMediaSize(val name: String, val widthMm: Double, val heightMm: Double) {
    val label: String
        get() {
            val parts = name.split('_')
            val base = parts.getOrNull(1)?.uppercase() ?: name
            return "$base (${fmt(widthMm)}x${fmt(heightMm)} mm)"
        }

    companion object {
        private val SIZE = Regex("""^([a-z0-9-]+)_([a-z0-9.\-]+)_(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)(mm|in)$""")

        fun parse(name: String): PwgMediaSize? {
            val m = SIZE.matchEntire(name.trim()) ?: return null
            val factor = if (m.groupValues[5] == "in") 25.4 else 1.0
            val w = m.groupValues[3].toDouble() * factor
            val h = m.groupValues[4].toDouble() * factor
            if (w <= 0 || h <= 0) return null
            return PwgMediaSize(name, w, h)
        }

        private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
    }
}
