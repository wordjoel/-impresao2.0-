package br.com.atos.printbridge.domain.ipp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Codificação binária do IPP (RFC 8010) — só o necessário para o Print Bridge conversar com
 * impressoras de escritório: Get-Printer-Attributes (identificar e ler capacidades sem imprimir)
 * e Print-Job. Lógica pura, sem rede, testada byte a byte.
 */
object IppCodec {
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 1

    const val OP_PRINT_JOB = 0x0002
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    // Delimitadores de grupo.
    const val TAG_OPERATION = 0x01
    const val TAG_JOB = 0x02
    const val TAG_END = 0x03
    const val TAG_PRINTER = 0x04
    const val TAG_UNSUPPORTED = 0x05

    // Tags de valor.
    const val TAG_UNSUPPORTED_VALUE = 0x10
    const val TAG_UNKNOWN = 0x12
    const val TAG_NO_VALUE = 0x13
    const val TAG_INTEGER = 0x21
    const val TAG_BOOLEAN = 0x22
    const val TAG_ENUM = 0x23
    const val TAG_DATETIME = 0x31
    const val TAG_RESOLUTION = 0x32
    const val TAG_RANGE = 0x33
    const val TAG_BEG_COLLECTION = 0x34
    const val TAG_TEXT_WITH_LANG = 0x35
    const val TAG_NAME_WITH_LANG = 0x36
    const val TAG_END_COLLECTION = 0x37
    const val TAG_TEXT = 0x41
    const val TAG_NAME = 0x42
    const val TAG_KEYWORD = 0x44
    const val TAG_URI = 0x45
    const val TAG_CHARSET = 0x47
    const val TAG_LANGUAGE = 0x48
    const val TAG_MIME = 0x49
    const val TAG_MEMBER_NAME = 0x4A

    /** Um atributo de pedido: nome, tag de valor e um ou mais valores (texto ou inteiro). */
    data class Attr(val tag: Int, val name: String, val values: List<Any>) {
        constructor(tag: Int, name: String, value: Any) : this(tag, name, listOf(value))
    }

    data class Group(val tag: Int, val attributes: List<Attr>)

    fun encodeRequest(operation: Int, requestId: Int, groups: List<Group>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION_MAJOR)
            out.writeByte(VERSION_MINOR)
            out.writeShort(operation)
            out.writeInt(requestId)
            groups.forEach { group ->
                out.writeByte(group.tag)
                group.attributes.forEach { attr ->
                    attr.values.forEachIndexed { i, value ->
                        out.writeByte(attr.tag)
                        // Valores adicionais do mesmo atributo vão com nome vazio (RFC 8010 §3.1.5).
                        val name = if (i == 0) attr.name.toByteArray(Charsets.US_ASCII) else ByteArray(0)
                        out.writeShort(name.size)
                        out.write(name)
                        when (attr.tag) {
                            TAG_INTEGER, TAG_ENUM -> {
                                out.writeShort(4)
                                out.writeInt((value as Number).toInt())
                            }
                            TAG_BOOLEAN -> {
                                out.writeShort(1)
                                out.writeByte(if (value as Boolean) 1 else 0)
                            }
                            else -> {
                                val text = value.toString().toByteArray(Charsets.UTF_8)
                                out.writeShort(text.size)
                                out.write(text)
                            }
                        }
                    }
                }
            }
            out.writeByte(TAG_END)
        }
        return bytes.toByteArray()
    }

    /**
     * Operação padrão com os três atributos obrigatórios (charset, idioma, printer-uri) mais
     * requesting-user-name — a ordem importa: charset e idioma precisam vir primeiro.
     */
    fun operationGroup(printerUri: String, vararg extra: Attr): Group = Group(
        TAG_OPERATION,
        listOf(
            Attr(TAG_CHARSET, "attributes-charset", "utf-8"),
            Attr(TAG_LANGUAGE, "attributes-natural-language", "pt-br"),
            Attr(TAG_URI, "printer-uri", printerUri),
            Attr(TAG_NAME, "requesting-user-name", "atos-print-bridge"),
        ) + extra,
    )

    // ------------------------------------------------------------------ resposta

    /** Resolução como a impressora declara: x/y e unidade (3 = dpi, 4 = pontos por cm). */
    data class Resolution(val x: Int, val y: Int, val units: Int) {
        /**
         * Em dpi. Impressoras que declaram em pontos/cm usam 118/236 para 300/600 dpi; a conversão
         * exata dá 299,7/599,4, então valores a menos de 1% de uma resolução nominal viram ela.
         */
        val dpi: Int
            get() {
                if (units != 4) return x
                val exact = x * 2.54
                return NOMINAL_DPI.firstOrNull { Math.abs(it - exact) / it < 0.01 } ?: Math.round(exact).toInt()
            }
    }

    private val NOMINAL_DPI = listOf(150, 200, 203, 300, 360, 600, 720, 1200, 2400)

    data class Response(
        val statusCode: Int,
        val requestId: Int,
        /** Atributos de nível superior, por grupo (operação, impressora, job, não suportado). */
        val groups: List<Pair<Int, Map<String, List<Any>>>>,
    ) {
        val isSuccess: Boolean get() = statusCode in 0x0000..0x00FF

        /** Primeiro grupo que tem o atributo — na prática, operação/impressora/job não se repetem. */
        fun values(name: String): List<Any> = groups.firstNotNullOfOrNull { it.second[name] } ?: emptyList()

        fun strings(name: String): List<String> = values(name).filterIsInstance<String>()
        fun string(name: String): String? = strings(name).firstOrNull()?.takeIf { it.isNotBlank() }
        fun ints(name: String): List<Int> = values(name).filterIsInstance<Int>()
        fun int(name: String): Int? = ints(name).firstOrNull()
        fun resolutions(name: String): List<Resolution> = values(name).filterIsInstance<Resolution>()
    }

    class MalformedResponseException(message: String) : Exception(message)

    /**
     * Decodifica a resposta. Coleções (media-col-database etc.) são percorridas mas não expostas:
     * só atributos de nível superior interessam aqui, e ignorar a estrutura quebraria o parser.
     */
    fun decodeResponse(data: ByteArray): Response {
        if (data.size < 8) throw MalformedResponseException("resposta IPP curta demais (${data.size} bytes)")
        val buf = ByteBuffer.wrap(data)
        buf.get() // versão maior
        buf.get() // versão menor
        val status = buf.short.toInt() and 0xFFFF
        val requestId = buf.int

        val groups = mutableListOf<Pair<Int, MutableMap<String, MutableList<Any>>>>()
        var current: MutableMap<String, MutableList<Any>>? = null
        var lastName: String? = null
        var depth = 0

        while (buf.hasRemaining()) {
            val tag = buf.get().toInt() and 0xFF
            if (tag == TAG_END) break
            if (tag < 0x10) {
                current = linkedMapOf()
                groups += tag to current
                lastName = null
                continue
            }
            if (buf.remaining() < 2) throw MalformedResponseException("atributo truncado")
            val nameLen = buf.short.toInt() and 0xFFFF
            val name = readString(buf, nameLen, Charsets.US_ASCII)
            if (buf.remaining() < 2) throw MalformedResponseException("valor truncado em '$name'")
            val valueLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < valueLen) throw MalformedResponseException("valor de '$name' excede a resposta")
            val raw = ByteArray(valueLen).also { buf.get(it) }

            when (tag) {
                TAG_BEG_COLLECTION -> { depth++; continue }
                TAG_END_COLLECTION -> { depth = (depth - 1).coerceAtLeast(0); continue }
            }
            if (depth > 0) continue

            val group = current ?: throw MalformedResponseException("atributo fora de grupo")
            val value = decodeValue(tag, raw) ?: continue
            if (name.isNotEmpty()) lastName = name
            val key = lastName ?: continue
            group.getOrPut(key) { mutableListOf() }.add(value)
        }
        return Response(status, requestId, groups)
    }

    private fun decodeValue(tag: Int, raw: ByteArray): Any? = when (tag) {
        TAG_INTEGER, TAG_ENUM -> if (raw.size == 4) ByteBuffer.wrap(raw).int else null
        TAG_BOOLEAN -> raw.firstOrNull()?.let { it.toInt() != 0 }
        TAG_RESOLUTION -> if (raw.size == 9) {
            val b = ByteBuffer.wrap(raw)
            Resolution(b.int, b.int, b.get().toInt())
        } else null
        TAG_RANGE -> if (raw.size == 8) ByteBuffer.wrap(raw).let { it.int..it.int } else null
        TAG_TEXT_WITH_LANG, TAG_NAME_WITH_LANG -> decodeWithLanguage(raw)
        TAG_NO_VALUE, TAG_UNKNOWN, TAG_UNSUPPORTED_VALUE, TAG_DATETIME -> null
        in 0x40..0x5F -> String(raw, Charsets.UTF_8)
        else -> null
    }

    /** textWithLanguage: [len idioma][idioma][len texto][texto] — só o texto interessa. */
    private fun decodeWithLanguage(raw: ByteArray): String? {
        if (raw.size < 4) return null
        val b = ByteBuffer.wrap(raw)
        val langLen = b.short.toInt() and 0xFFFF
        if (b.remaining() < langLen + 2) return null
        b.position(b.position() + langLen)
        val textLen = b.short.toInt() and 0xFFFF
        if (b.remaining() < textLen) return null
        return readString(b, textLen, Charsets.UTF_8)
    }

    private fun readString(buf: ByteBuffer, len: Int, charset: java.nio.charset.Charset): String {
        if (buf.remaining() < len) throw MalformedResponseException("texto truncado")
        val bytes = ByteArray(len).also { buf.get(it) }
        return String(bytes, charset)
    }

    /** Nome legível dos status mais comuns, para mensagens de erro técnicas honestas. */
    fun statusName(code: Int): String = when (code) {
        0x0000 -> "successful-ok"
        0x0001 -> "successful-ok-ignored-or-substituted-attributes"
        0x0400 -> "client-error-bad-request"
        0x0401 -> "client-error-forbidden"
        0x0402 -> "client-error-not-authenticated"
        0x0403 -> "client-error-not-authorized"
        0x0404 -> "client-error-not-possible"
        0x0406 -> "client-error-not-found"
        0x040A -> "client-error-document-format-not-supported"
        0x040B -> "client-error-attributes-or-values-not-supported"
        0x0500 -> "server-error-internal-error"
        0x0501 -> "server-error-operation-not-supported"
        0x0502 -> "server-error-service-unavailable"
        0x0503 -> "server-error-version-not-supported"
        0x0506 -> "server-error-not-accepting-jobs"
        0x0507 -> "server-error-busy"
        else -> "0x%04X".format(code)
    }
}
