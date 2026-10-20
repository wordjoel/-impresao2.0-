package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.model.PrinterLanguage
import java.text.Normalizer

/** Valor recusado por uma lacuna. MISSING → MISSING_VARIABLES, INVALID → INVALID_VARIABLE_VALUE na ponte. */
class SlotValueException(val field: String, val code: Code, message: String) : Exception(message) {
    enum class Code { MISSING, INVALID }
}

/**
 * Monta os bytes de UMA etiqueta a partir do pacote. Espelho exato de `fillSlot`/`assembleLabel` em
 * designer/packages/exporters/src/coletor.ts: qualquer diferença aqui imprime outra etiqueta, por
 * isso o pacote traz vetores de conformidade e o teste cruzado (fixture gerada pelo TS) confere
 * cada regra. Ordem por lacuna: controle → validação/normalização do tipo → tamanho (recusa,
 * nunca corta) → escape da linguagem.
 */
object ColetorAssembler {

    const val MAX_COPIES = 9999

    fun assemble(target: PackageTarget, values: Map<String, String>, copies: Int = 1): ByteArray {
        require(copies in 1..MAX_COPIES) { "Cópias entre 1 e $MAX_COPIES" }
        val slots = target.slots.associateBy { it.id }
        val sb = StringBuilder()
        for (seg in target.segments) {
            when (seg) {
                is Segment.Lit -> sb.append(seg.text)
                is Segment.SlotRef -> {
                    val slot = slots[seg.slot] ?: error("Pacote sem a lacuna ${seg.slot}")
                    sb.append(fillSlot(slot, values[slot.field], target.language))
                }
                Segment.Copies -> sb.append(copies)
            }
        }
        val text = sb.toString()
        return if (target.language == PrinterLanguage.TSPL) text.toByteArray(Charsets.ISO_8859_1) else text.toByteArray(Charsets.UTF_8)
    }

    fun fillSlot(slot: PackageSlot, raw: String?, language: PrinterLanguage): String {
        fun invalid(why: String) = SlotValueException(slot.field, SlotValueException.Code.INVALID, "${slot.field}: $why")
        // v1: todo campo é obrigatório (linha vazia some do comando no gerador e mudaria a geometria).
        if (raw.isNullOrEmpty()) throw SlotValueException(slot.field, SlotValueException.Code.MISSING, "${slot.field}: campo obrigatório vazio")
        if (hasControlOrLoneSurrogate(raw)) throw invalid("caractere de controle ou inválido não é aceito")

        val value = when (slot.kind) {
            SlotKind.TEXT, SlotKind.QR -> raw
            else -> normalize1D(slot.kind, raw) ?: throw invalid(barcodeMessage(slot.kind, raw))
        }

        val length = if (slot.kind == SlotKind.QR) {
            value.toByteArray(Charsets.UTF_8).size
        } else {
            val v = if (language == PrinterLanguage.TSPL) nfc(value) else value
            v.codePointCount(0, v.length)
        }
        if (length > slot.maxLength) throw invalid("passou do limite de ${slot.maxLength} (tem $length)")

        if (language == PrinterLanguage.ZPL) return zplEscape(value)
        // TSPL normaliza o comando inteiro (NFC): acento solto no início se juntaria ao texto fixo.
        if (LEADING_MARK.containsMatchIn(value)) throw invalid("começa com acento solto")
        val cp = toCp1252(nfc(value)) ?: throw invalid("caractere fora do Windows-1252 (a impressora não tem)")
        return cp.replace("\"", "\\[\"]")
    }

    // ---------------------------------------------------------------- regras copiadas do TS

    private val LEADING_MARK = Regex("^\\p{M}")

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** /[\u0000-\u001f\u007f]|\p{Cs}/u do TS: controle C0, DEL e surrogate sem par. */
    internal fun hasControlOrLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code <= 0x1F || c.code == 0x7F) return true
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    fun zplEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '^' || c == '~' || c == '_' || c == '\\') sb.append('_').append("%02X".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    private val CP1252_EXTRA: Map<Int, Int> = mapOf(
        0x20AC to 0x80, 0x201A to 0x82, 0x0192 to 0x83, 0x201E to 0x84, 0x2026 to 0x85, 0x2020 to 0x86, 0x2021 to 0x87,
        0x02C6 to 0x88, 0x2030 to 0x89, 0x0160 to 0x8A, 0x2039 to 0x8B, 0x0152 to 0x8C, 0x017D to 0x8E, 0x2018 to 0x91,
        0x2019 to 0x92, 0x201C to 0x93, 0x201D to 0x94, 0x2022 to 0x95, 0x2013 to 0x96, 0x2014 to 0x97, 0x02DC to 0x98,
        0x2122 to 0x99, 0x0161 to 0x9A, 0x203A to 0x9B, 0x0153 to 0x9C, 0x017E to 0x9E, 0x0178 to 0x9F,
    )

    /** Cada caractere vira o byte Windows-1252 (como char 0–255); null se algum não existir. */
    internal fun toCp1252(s: String): String? {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp < 0x80 || cp in 0xA0..0xFF -> sb.append(cp.toChar())
                else -> sb.append((CP1252_EXTRA[cp] ?: return null).toChar())
            }
        }
        return sb.toString()
    }

    /** WhiteSpace + LineTerminator do JavaScript (o que `trim()` e `\s` cobrem). */
    private fun isJsSpace(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' || c == '\u00A0' || c == '\uFEFF' ||
            c == '\u2028' || c == '\u2029' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private fun jsTrim(s: String): String = s.dropWhile(::isJsSpace).dropLastWhile(::isJsSpace)

    private fun gs1Check(digits: String): Char {
        var sum = 0
        for (i in digits.indices) {
            val fromRight = digits.length - 1 - i
            sum += (digits[i] - '0') * if (fromRight % 2 == 0) 3 else 1
        }
        return '0' + (10 - sum % 10) % 10
    }

    private fun allDigits(s: String, n: Int) = s.length == n && s.all { it in '0'..'9' }

    /** `normalize1D(...).printerData` do engine: EAN/UPC sem o verificador; Code 128 como veio (aparado). Null = recusa. */
    internal fun normalize1D(kind: SlotKind, raw: String): String? {
        val value = jsTrim(raw)
        if (value.isEmpty()) return null
        val d = value.filterNot(::isJsSpace)
        return when (kind) {
            SlotKind.EAN13 -> gs1Data(d, 12)
            SlotKind.UPCA -> gs1Data(d, 11)
            SlotKind.EAN8 -> gs1Data(d, 7)
            SlotKind.CODE128 -> value.takeIf { v -> v.all { it.code in 0x20..0x7E } }
            SlotKind.TEXT, SlotKind.QR -> value
        }
    }

    /** n dígitos (verificador calculado pela impressora) ou n+1 com o verificador certo. */
    private fun gs1Data(d: String, n: Int): String? = when {
        allDigits(d, n) -> d
        allDigits(d, n + 1) && gs1Check(d.substring(0, n)) == d[n] -> d.substring(0, n)
        else -> null
    }

    private fun barcodeMessage(kind: SlotKind, raw: String): String = when (kind) {
        SlotKind.EAN13 -> "EAN-13 precisa de 12 ou 13 dígitos com verificador certo (\"$raw\")"
        SlotKind.UPCA -> "UPC-A precisa de 11 ou 12 dígitos com verificador certo (\"$raw\")"
        SlotKind.EAN8 -> "EAN-8 precisa de 7 ou 8 dígitos com verificador certo (\"$raw\")"
        SlotKind.CODE128 -> "Code 128 aceita só ASCII imprimível; remova acentos (\"$raw\")"
        else -> "valor inválido"
    }
}
