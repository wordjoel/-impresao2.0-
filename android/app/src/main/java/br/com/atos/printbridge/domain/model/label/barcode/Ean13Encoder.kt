package br.com.atos.printbridge.domain.model.label.barcode

/**
 * EAN-13 (13 dígitos). Aceita 12 dígitos (calcula o verificador) ou 13 (valida o verificador).
 * Simbologia pública: 3 guardas + 6 dígitos esquerdos + guarda central + 6 dígitos direitos = 95
 * módulos.
 */
object Ean13Encoder {

    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011",
    )
    private val G = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111",
    )
    private val R = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100",
    )
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
    )

    const val MODULE_COUNT = 95

    /** Calcula o dígito verificador de um EAN-13 a partir dos 12 primeiros dígitos. */
    fun checkDigit(first12: String): Int {
        require(first12.length == 12 && first12.all { it.isDigit() }) { "EAN-13 exige 12 dígitos" }
        var sum = 0
        first12.forEachIndexed { index, c ->
            val digit = c - '0'
            sum += if (index % 2 == 0) digit else digit * 3
        }
        return (10 - sum % 10) % 10
    }

    /** Devolve a lista de módulos (`true` = barra) ou `null` se o valor não for um EAN-13 válido. */
    fun encodeOrNull(value: String): List<Boolean>? {
        val digits = value.trim()
        val normalized = when {
            digits.length == 12 && digits.all { it.isDigit() } -> digits + checkDigit(digits)
            digits.length == 13 && digits.all { it.isDigit() } -> {
                if (checkDigit(digits.substring(0, 12)) != digits[12] - '0') return null
                digits
            }
            else -> return null
        }

        val parity = PARITY[normalized[0] - '0']
        val bits = StringBuilder(MODULE_COUNT)
        bits.append("101")
        for (i in 1..6) {
            val digit = normalized[i] - '0'
            bits.append(if (parity[i - 1] == 'L') L[digit] else G[digit])
        }
        bits.append("01010")
        for (i in 7..12) {
            bits.append(R[normalized[i] - '0'])
        }
        bits.append("101")

        return bits.map { it == '1' }
    }
}
