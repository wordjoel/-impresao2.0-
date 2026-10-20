package br.com.atos.printbridge.domain.coletor

/**
 * Leitor JSON estrito (RFC 8259), Kotlin puro: `org.json` do Android não roda em teste de JVM e o
 * app não tem outra biblioteca JSON. Só lê: objeto → Map (ordem preservada), array → List,
 * número → Long quando inteiro, senão Double; string, Boolean e null. Qualquer desvio (vírgula
 * sobrando, escape inválido, lixo no fim) é erro — o pacote vem assinado do PC, então JSON fora do
 * padrão é sinal de problema, não algo a tolerar.
 */
object StrictJson {

    class JsonException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.ws()
        val v = r.value(0)
        r.ws()
        if (r.i != text.length) r.fail("conteúdo depois do fim")
        return v
    }

    private const val MAX_DEPTH = 64

    private class Reader(val s: String) {
        var i = 0

        fun fail(why: String): Nothing = throw JsonException("JSON inválido na posição $i: $why")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("aninhamento demais")
            if (i >= s.length) fail("fim inesperado")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("esperado $w")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("esperado nome entre aspas")
                val k = str()
                if (out.containsKey(k)) fail("chave repetida \"$k\"")
                ws()
                if (i >= s.length || s[i] != ':') fail("esperado ':'")
                i++
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("objeto sem fim")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> {
                        i--
                        fail("esperado ',' ou '}'")
                    }
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                ws()
                out.add(value(depth + 1))
                ws()
                if (i >= s.length) fail("array sem fim")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> {
                        i--
                        fail("esperado ',' ou ']'")
                    }
                }
            }
        }

        fun str(): String {
            i++ // aspas de abertura
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("string sem fim")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("escape sem fim")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u incompleto")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("\\u inválido")
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("escape \\$e inválido")
                        }
                    }
                    c < ' ' -> fail("caractere de controle sem escape")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail("número incompleto")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i].isAsciiDigit()) i++
            } else {
                fail("valor inesperado '${s[i]}'")
            }
            var integer = true
            if (i < s.length && s[i] == '.') {
                integer = false
                i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("decimal sem dígitos")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integer = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("expoente sem dígitos")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            val text = s.substring(start, i)
            return if (integer) text.toLongOrNull() ?: fail("inteiro grande demais") else text.toDouble()
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
