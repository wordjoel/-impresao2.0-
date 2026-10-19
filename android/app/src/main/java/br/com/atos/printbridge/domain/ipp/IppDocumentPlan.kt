package br.com.atos.printbridge.domain.ipp

import br.com.atos.printbridge.domain.model.PrinterCapabilities
import br.com.atos.printbridge.domain.model.PwgMediaSize
import br.com.atos.printbridge.domain.raster.PwgRasterType

/**
 * Como gerar um documento para uma impressora IPP, decidido SÓ pelas capacidades que ela
 * declarou: formato (PDF se ela interpreta PDF; senão PWG Raster), papel (o padrão dela) e
 * resolução (uma das que ela aceita para raster). Sem capacidade declarada, não há plano — e o
 * motivo diz exatamente o que faltou.
 */
sealed class IppDocumentPlan {
    abstract val media: PwgMediaSize

    data class Pdf(override val media: PwgMediaSize) : IppDocumentPlan()
    data class PwgRaster(override val media: PwgMediaSize, val dpi: Int, val type: PwgRasterType) : IppDocumentPlan()

    class Unsupported(val reason: String) : Exception(reason)

    companion object {
        /** Teto de resolução do raster gerado: acima disso a página não cabe no limite da fila nem na memória do coletor. */
        const val MAX_RASTER_DPI = 300

        fun choose(capabilities: PrinterCapabilities?): IppDocumentPlan {
            val caps = capabilities ?: throw Unsupported("a impressora ainda não informou suas capacidades — use \"Testar conexão\"")
            val media = chooseMedia(caps)
            if (caps.supportsPdf) return Pdf(media)
            if (caps.supportsPwgRaster) {
                val type = PwgRasterType.preferredOf(caps.pwgRasterTypes)!!
                val dpi = chooseDpi(caps.pwgRasterResolutionsDpi.ifEmpty { caps.resolutionsDpi })
                    ?: throw Unsupported("a impressora aceita PWG Raster mas não informou a resolução")
                return PwgRaster(media, dpi, type)
            }
            val formats = caps.documentFormats.ifEmpty { listOf("nenhum informado") }.joinToString()
            throw Unsupported("formatos aceitos pela impressora: $formats — o Print Bridge gera application/pdf ou image/pwg-raster (black_1/sgray_8)")
        }

        fun chooseMedia(caps: PrinterCapabilities): PwgMediaSize =
            caps.defaultMedia?.let(PwgMediaSize::parse)
                ?: caps.media.firstNotNullOfOrNull(PwgMediaSize::parse)
                ?: throw Unsupported("a impressora não informou um tamanho de papel reconhecível")

        /** Maior resolução aceita até [MAX_RASTER_DPI]; se todas forem maiores, a menor delas. */
        fun chooseDpi(declared: List<Int>): Int? {
            val valid = declared.filter { it > 0 }.distinct()
            return valid.filter { it <= MAX_RASTER_DPI }.maxOrNull() ?: valid.minOrNull()
        }
    }
}
