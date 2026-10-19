package br.com.atos.printbridge.domain.model.label

/**
 * Serializa a lista de elementos de uma etiqueta para uma coluna `TEXT` única no Room.
 *
 * Por que não JSON: o app não tem biblioteca de serialização (nem `kotlinx.serialization`, nem
 * Moshi/Gson) e esta rodada **não adiciona dependências** (ver PESOHUB-LABEL-MODULE-AUDIT.md §4).
 * O PesoHub guardava o layout como JSON (`layout: Json?`), mas lá já existia `JSON.parse` nativo do
 * Node — no Android não existe equivalente sem dependência.
 *
 * Formato: um elemento por linha, campos separados por TAB (`\t`), valores escapados
 * (`\` → `\\`, TAB → `\t`, LF → `\n`, CR → `\r`). Isso torna o codec determinístico, testável e
 * tolerante: uma linha malformada é ignorada na leitura em vez de derrubar o app.
 */
object LabelLayoutCodec {

    private const val FIELD_SEP = '\t'
    private const val RECORD_SEP = '\n'
    private const val ESCAPE = '\\'
    private const val MAX_RECORDS = 500

    fun encode(elements: List<LabelElement>): String =
        elements.joinToString(RECORD_SEP.toString()) { encodeElement(it) }

    fun decode(raw: String?): List<LabelElement> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEP)
            .asSequence()
            .filter { it.isNotBlank() }
            .take(MAX_RECORDS)
            .mapNotNull { decodeElement(it) }
            .toList()
    }

    private fun encodeElement(element: LabelElement): String = listOf(
        element.id,
        element.type.name,
        element.xMm.toString(),
        element.yMm.toString(),
        element.widthMm.toString(),
        element.heightMm.toString(),
        element.text,
        element.field?.name ?: "",
        element.barcodeType.name,
        element.rotationDeg.toString(),
        element.align.name,
        element.fontSizeMm.toString(),
        element.thicknessMm.toString(),
    ).joinToString(FIELD_SEP.toString()) { escape(it) }

    private fun decodeElement(line: String): LabelElement? {
        val parts = splitEscaped(line)
        if (parts.size < 13) return null
        val type = enumOrNull<LabelElementType>(parts[1]) ?: return null
        return LabelElement(
            id = parts[0].ifBlank { return null },
            type = type,
            xMm = parts[2].toFloatOrNull() ?: 0f,
            yMm = parts[3].toFloatOrNull() ?: 0f,
            widthMm = parts[4].toFloatOrNull() ?: type.defaultWidthMm,
            heightMm = parts[5].toFloatOrNull() ?: type.defaultHeightMm,
            text = parts[6],
            field = enumOrNull<LabelField>(parts[7]),
            barcodeType = enumOrNull<BarcodeType>(parts[8]) ?: BarcodeType.CODE128,
            rotationDeg = parts[9].toIntOrNull() ?: 0,
            align = enumOrNull<LabelAlign>(parts[10]) ?: LabelAlign.LEFT,
            fontSizeMm = parts[11].toFloatOrNull() ?: 3f,
            thicknessMm = parts[12].toFloatOrNull() ?: 0.5f,
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                ESCAPE -> append(ESCAPE).append(ESCAPE)
                FIELD_SEP -> append(ESCAPE).append('t')
                RECORD_SEP -> append(ESCAPE).append('n')
                '\r' -> append(ESCAPE).append('r')
                else -> append(c)
            }
        }
    }

    private fun splitEscaped(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in line) {
            if (escaped) {
                current.append(
                    when (c) {
                        't' -> FIELD_SEP
                        'n' -> RECORD_SEP
                        'r' -> '\r'
                        else -> c
                    }
                )
                escaped = false
            } else {
                when (c) {
                    ESCAPE -> escaped = true
                    FIELD_SEP -> {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
        }
        fields += current.toString()
        return fields
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        if (name.isBlank()) null else enumValues<T>().firstOrNull { it.name == name }
}
