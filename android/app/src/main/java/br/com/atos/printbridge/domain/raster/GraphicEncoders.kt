package br.com.atos.printbridge.domain.raster

/**
 * Encapsula uma página 1-bit como etiqueta ZPL completa usando `^GFA` (Graphic Field, ASCII hex),
 * enviado inline num único `^XA...^XZ` — sem `~DG` prévio, porque cada página de documento é
 * única e gravá-la na memória da impressora só gastaria flash.
 *
 * Formato: `^GFA,<bytes totais>,<bytes totais>,<bytes por linha>,<hex>` — hex sem compressão
 * (ZPL ASCII compression / Z64 ficam para quando a validação física mostrar que o tamanho pesa).
 */
object ZplGraphicEncoder {
    fun encode(page: MonoBitmap): String {
        val total = page.data.size
        return buildString(total * 2 + 96) {
            append("^XA\n")
            append("^PW").append(page.width).append('\n')
            append("^LL").append(page.height).append('\n')
            append("^FO0,0^GFA,").append(total).append(',').append(total).append(',')
                .append(page.bytesPerRow).append(',')
            page.data.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            append("^FS\n^XZ")
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

/**
 * Encapsula uma página 1-bit como job TSPL (TSC) com o comando `BITMAP x,y,largura_bytes,altura,modo,dados`.
 * Escolhido no lugar de `PUTBMP` (sugerido no prompt mestre) porque PUTBMP exige antes um
 * `DOWNLOAD` do .BMP para a memória da impressora — duas etapas e gravação em flash a cada página.
 * O BITMAP inline é o "caminho mais direto" previsto no ponto 8.4 do prompt.
 *
 * Os dados são BINÁRIOS: o retorno é ByteArray, e quem precisar guardá-lo como texto (fila Room)
 * usa ISO-8859-1, que mapeia 1:1 byte↔char (ver [PrintPayloadType.TSPL]).
 *
 * ATENÇÃO — polaridade: no BITMAP da TSC o bit **0 queima o ponto** (inverso do ZPL). Por isso os
 * bytes são invertidos aqui. Confirmar na impressora real (validação física da Etapa 4).
 * `SIZE` usa o tamanho do papel do perfil; `GAP` não é enviado de propósito, para não desfazer a
 * calibração de gap já feita na impressora.
 */
object TsplGraphicEncoder {
    fun encode(page: MonoBitmap, labelWidthMm: Int, labelHeightMm: Int): ByteArray {
        val header = "SIZE $labelWidthMm mm,$labelHeightMm mm\r\n" +
            "CLS\r\n" +
            "BITMAP 0,0,${page.bytesPerRow},${page.height},0,"
        val footer = "\r\nPRINT 1\r\n"
        val inverted = ByteArray(page.data.size) { i -> (page.data[i].toInt() xor 0xFF).toByte() }
        return header.toByteArray(Charsets.US_ASCII) + inverted + footer.toByteArray(Charsets.US_ASCII)
    }
}
