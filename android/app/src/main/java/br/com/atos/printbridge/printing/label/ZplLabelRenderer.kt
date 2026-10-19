package br.com.atos.printbridge.printing.label

import br.com.atos.printbridge.domain.model.label.BarcodeType
import br.com.atos.printbridge.domain.model.label.LabelAlign
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.model.label.LabelZplRenderer
import javax.inject.Inject

/**
 * Renderiza uma etiqueta em **ZPL** (TSC ML241/MB241), a partir do layout em milímetros.
 *
 * Este é código NOVO, não importado do PesoHub — na origem não existe geração de ZPL (ver
 * PESOHUB-LABEL-MODULE-AUDIT.md §2.1/§5). Ele também **não** abre socket nem enfileira nada: só
 * devolve a string que o `PrintJob` existente vai transportar. A impressão física continua sendo
 * responsabilidade única do `PrintEngine`/`TcpPrinterTransport`.
 *
 * Decisões:
 * - mm→dots por `template.dpi` (`^PW`/`^LL` e todas as coordenadas). Assim o mesmo layout serve
 *   para 203 dpi e 300 dpi sem reedi-ção.
 * - Texto é transliterado para ASCII e tem `^`/`~`/controle substituídos por espaço: o ZPL trata
 *   `^` e `~` como início de comando, e a página de código padrão da TSC não garante acentuação.
 *   (Alternativa seria `^FH` com bytes hexadecimais; fica documentada como evolução, não default.)
 * - `copies` **não** é tratado aqui: o `PrintEngine` já repete o bloco no envio (`job.copies`).
 */
class ZplLabelRenderer @Inject constructor() : LabelZplRenderer {

    override fun render(template: LabelTemplate, data: LabelData): String = buildString {
        append("^XA\n")
        append("^PW${template.dots(template.widthMm)}\n")
        append("^LL${template.dots(template.heightMm)}\n")
        append("^LH0,0\n")
        template.elements.forEach { append(renderElement(it, template, data)) }
        append("^XZ")
    }

    private fun renderElement(element: LabelElement, template: LabelTemplate, data: LabelData): String =
        when (element.type) {
            LabelElementType.TEXTO,
            LabelElementType.CAMPO,
            -> renderText(element, template, data)

            LabelElementType.CODIGO_BARRAS -> renderBarcode(element, template, data)
            LabelElementType.QRCODE -> renderQrCode(element, template, data)
            LabelElementType.LINHA,
            LabelElementType.CAIXA,
            -> renderBox(element, template)

            LabelElementType.IMAGEM -> renderImagePlaceholder(element, template)
        }

    private fun renderText(element: LabelElement, template: LabelTemplate, data: LabelData): String {
        val value = sanitize(element.resolveValue(data))
        if (value.isBlank()) return ""
        val origin = origin(element, template)
        val heightDots = fontSizeDots(element, template)
        val widthDots = template.dots(element.widthMm)
        val align = when (element.align) {
            LabelAlign.LEFT -> "L"
            LabelAlign.CENTER -> "C"
            LabelAlign.RIGHT -> "R"
        }
        return buildString {
            append("^FO${origin.first},${origin.second}")
            if (widthDots > 0) append("^FB$widthDots,1,0,$align,,")
            append("^A0${orientation(element.rotationDeg)},$heightDots")
            append("^FD$value^FS\n")
        }
    }

    private fun renderBarcode(element: LabelElement, template: LabelTemplate, data: LabelData): String {
        val origin = origin(element, template)
        val heightDots = template.dots(element.heightMm).coerceAtLeast(10)
        val raw = element.resolveValue(data).trim()
        if (raw.isBlank()) return ""

        return when (element.barcodeType) {
            BarcodeType.EAN13 -> {
                val digits = raw.filter { it.isDigit() }
                if (digits.length != 12 && digits.length != 13) {
                    // Dado incompatível com EAN-13: imprime o valor como texto para não gerar um
                    // símbolo inválido silenciosamente.
                    return "^FO${origin.first},${origin.second}^A0${orientation(element.rotationDeg)},$heightDots^FD${sanitize(raw)}^FS\n"
                }
                "^FO${origin.first},${origin.second}^BY2,2.0,$heightDots^BE${orientation(element.rotationDeg)},$heightDots,Y,N^FD$digits^FS\n"
            }

            BarcodeType.CODE128 ->
                "^FO${origin.first},${origin.second}^BY2,2.0,$heightDots^BC${orientation(element.rotationDeg)},$heightDots,Y,N,N^FD${sanitize(raw)}^FS\n"
        }
    }

    private fun renderQrCode(element: LabelElement, template: LabelTemplate, data: LabelData): String {
        val value = sanitize(element.resolveValue(data))
        if (value.isBlank()) return ""
        val origin = origin(element, template)
        val heightDots = template.dots(element.heightMm).coerceAtLeast(20)
        // Magnificação ~ módulo de 1/33 da altura (o QR de versão média tem ~33 módulos).
        val magnification = (heightDots / 33).coerceIn(2, 10)
        return "^FO${origin.first},${origin.second}^BQN,2,$magnification^FDQA,$value^FS\n"
    }

    private fun renderBox(element: LabelElement, template: LabelTemplate): String {
        val origin = origin(element, template)
        val widthDots = template.dots(element.widthMm).coerceAtLeast(1)
        val heightDots = template.dots(element.heightMm).coerceAtLeast(1)
        val thickness = template.dots(element.thicknessMm).coerceAtLeast(1)
        return "^FO${origin.first},${origin.second}^GB$widthDots,$heightDots,$thickness^FS\n"
    }

    private fun renderImagePlaceholder(element: LabelElement, template: LabelTemplate): String {
        val origin = origin(element, template)
        val widthDots = template.dots(element.widthMm).coerceAtLeast(1)
        val heightDots = template.dots(element.heightMm).coerceAtLeast(1)
        // Imagem rasterizada (^GFA) exige um bitmap monocromático que este app não armazena na V1.
        // O elemento existe estruturalmente e imprime uma moldura de reserva — ver
        // LABEL-MODULE-MIGRATION-MAP.md (evolução).
        return "^FO${origin.first},${origin.second}^GB$widthDots,$heightDots,1^FS\n"
    }

    private fun origin(element: LabelElement, template: LabelTemplate): Pair<Int, Int> =
        template.dots(element.xMm) to template.dots(element.yMm)

    private fun fontSizeDots(element: LabelElement, template: LabelTemplate): Int =
        template.dots(element.fontSizeMm).coerceAtLeast(8)

    /** `^A0`/`^BC`/`^BE`/`^BQ` usam N (0°), R (90°), I (180°), B (270°). */
    private fun orientation(rotationDeg: Int): Char = when (((rotationDeg % 360) + 360) % 360) {
        90 -> 'R'
        180 -> 'I'
        270 -> 'B'
        else -> 'N'
    }

    companion object {
        /**
         * Deixa o texto seguro para `^FD`: translitera acentos (página de código padrão da TSC não
         * garante UTF-8) e neutraliza `^`/`~`/controle, que o ZPL interpretaria como comando.
         */
        fun sanitize(value: String): String = buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '^', '~' -> append(' ')
                    '\n', '\r', '\t' -> append(' ')
                    else -> if (c.code in 32..126) append(c) else append(transliterate(c))
                }
            }
        }.trim()

        private fun transliterate(c: Char): Char = when (c) {
            'á', 'à', 'â', 'ã', 'ä', 'å', 'ª', 'Á', 'À', 'Â', 'Ã', 'Ä' -> if (c.isUpperCase()) 'A' else 'a'
            'é', 'è', 'ê', 'ë', 'É', 'È', 'Ê', 'Ë' -> if (c.isUpperCase()) 'E' else 'e'
            'í', 'ì', 'î', 'ï', 'Í', 'Ì', 'Î', 'Ï' -> if (c.isUpperCase()) 'I' else 'i'
            'ó', 'ò', 'ô', 'õ', 'ö', 'º', 'Ó', 'Ò', 'Ô', 'Õ', 'Ö' -> if (c.isUpperCase()) 'O' else 'o'
            'ú', 'ù', 'û', 'ü', 'Ú', 'Ù', 'Û', 'Ü' -> if (c.isUpperCase()) 'U' else 'u'
            'ç', 'Ç' -> if (c.isUpperCase()) 'C' else 'c'
            'ñ', 'Ñ' -> if (c.isUpperCase()) 'N' else 'n'
            else -> '?'
        }
    }
}
