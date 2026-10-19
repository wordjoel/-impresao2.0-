package br.com.atos.printbridge.domain.model.label.barcode

import br.com.atos.printbridge.domain.model.label.BarcodeType

/**
 * Encoders de código de barras **próprios** (sem dependência nova) usados apenas pela
 * pré-visualização Compose. A impressão real não usa estas barras: o ZPL gera o símbolo na
 * impressora via `^BE`/`^BC` (ver `ZplLabelRenderer`).
 *
 * O PesoHub usava a biblioteca JavaScript `jsbarcode` (MIT) para EAN13 no preview; aqui ela é
 * inútil por ser JavaScript, então a simbologia foi reimplementada a partir das tabelas públicas do
 * padrão (ver PESOHUB-LABEL-MODULE-AUDIT.md §3).
 */
object BarcodeEncoder {

    /** Sequência de módulos: `true` = barra, `false` = espaço. */
    fun encode(type: BarcodeType, value: String): List<Boolean> = when (type) {
        BarcodeType.EAN13 -> Ean13Encoder.encodeOrNull(value) ?: emptyList()
        BarcodeType.CODE128 -> Code128Encoder.encodeOrNull(value) ?: emptyList()
    }
}
