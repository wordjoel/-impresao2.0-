package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.model.PrinterLanguage

/**
 * Pacote de etiqueta publicado pelo designer do PC (passo 6, contrato
 * docs/contratos/designer-coletor.openapi.yaml, schema LabelPackage). Já vem compilado: comandos
 * prontos (`Lit`) com lacunas (`SlotRef`) — o app só valida, escapa e concatena ([ColetorAssembler]).
 */
data class LabelPackage(
    val formatVersion: Int,
    val templateId: String,
    val name: String,
    val templateVersion: Int,
    val templateHash: String,
    val widthMm: Double,
    val heightMm: Double,
    val compiler: String,
    val compiledAt: String,
    val fields: List<PackageField>,
    val targets: List<PackageTarget>,
) {
    companion object {
        const val FORMAT = "athos-label-coletor"
        const val FORMAT_VERSION = 1

        /** Lê o packageJson (já conferido por hash e assinatura). Formato desconhecido → erro. */
        fun parse(packageJson: String): LabelPackage {
            val o = StrictJson.parse(packageJson).asObj("pacote")
            if (o.str("format") != FORMAT) throw ColetorPackageException(ColetorPackageException.Reason.FORMAT, "formato '${o["format"]}' não é $FORMAT")
            val version = o.int("formatVersion")
            if (version != FORMAT_VERSION) {
                throw ColetorPackageException(ColetorPackageException.Reason.FORMAT, "formatVersion $version: atualize o app (este lê $FORMAT_VERSION)")
            }
            return LabelPackage(
                formatVersion = version,
                templateId = o.str("templateId"),
                name = o.str("name"),
                templateVersion = o.int("templateVersion"),
                templateHash = o.str("templateHash"),
                widthMm = o.num("widthMm"),
                heightMm = o.num("heightMm"),
                compiler = o.str("compiler"),
                compiledAt = o.str("compiledAt"),
                fields = o.list("fields").map { PackageField.parse(it.asObj("campo")) },
                targets = o.list("targets").map { PackageTarget.parse(it.asObj("alvo")) }.also {
                    if (it.isEmpty()) throw malformed("pacote sem alvo")
                },
            )
        }
    }
}

data class PackageField(
    val name: String,
    val label: String,
    val required: Boolean,
    val maxLength: Int,
    val input: Input,
    val sample: String?,
) {
    enum class Input { SCAN, KEYBOARD, ANY }

    companion object {
        fun parse(o: Map<String, Any?>) = PackageField(
            name = o.str("name"),
            label = o.str("label"),
            required = o.bool("required"),
            maxLength = o.int("maxLength"),
            input = o.enum<Input>("input"),
            sample = o["sample"] as? String,
        )
    }
}

enum class SlotKind { TEXT, EAN13, EAN8, UPCA, CODE128, QR }

data class PackageSlot(val id: String, val field: String, val kind: SlotKind, val maxLength: Int) {
    companion object {
        fun parse(o: Map<String, Any?>): PackageSlot {
            val maxLength = o.int("maxLength")
            if (maxLength < 1) throw malformed("maxLength < 1")
            // transform (NONE/GS1_DATA) é derivado do kind no v1; só conferimos que é conhecido.
            o["transform"]?.let { if (it != "NONE" && it != "GS1_DATA") throw malformed("transform '$it'") }
            return PackageSlot(o.str("id"), o.str("field"), o.enum("kind"), maxLength)
        }
    }
}

sealed interface Segment {
    data class Lit(val text: String) : Segment
    data class SlotRef(val slot: String) : Segment
    data object Copies : Segment
}

data class ConformanceVector(val values: Map<String, String>, val copies: Int, val bytesSha256: String)

data class PackageTarget(
    val language: PrinterLanguage,
    val dpi: Int,
    val segments: List<Segment>,
    val slots: List<PackageSlot>,
    val conformance: List<ConformanceVector>,
) {
    companion object {
        fun parse(o: Map<String, Any?>): PackageTarget {
            val language = when (val l = o.str("language")) {
                "ZPL" -> PrinterLanguage.ZPL
                "TSPL" -> PrinterLanguage.TSPL
                else -> throw malformed("linguagem '$l'")
            }
            val segments = o.list("segments").map { raw ->
                val s = raw.asObj("segmento")
                when {
                    s.size != 1 -> throw malformed("segmento precisa de exatamente um de lit/slot/copies")
                    "lit" in s -> Segment.Lit(s.str("lit")).also {
                        // TSPL: 1 caractere = 1 byte (ISO-8859-1). Acima de U+00FF não cabe.
                        if (language == PrinterLanguage.TSPL && it.text.any { c -> c.code > 0xFF }) throw malformed("lit TSPL fora de 1 byte")
                    }
                    "slot" in s -> Segment.SlotRef(s.str("slot"))
                    s["copies"] == true -> Segment.Copies
                    else -> throw malformed("segmento desconhecido")
                }
            }
            val slots = o.list("slots").map { PackageSlot.parse(it.asObj("lacuna")) }
            val ids = slots.map { it.id }.toSet()
            if (ids.size != slots.size) throw malformed("lacuna repetida")
            segments.filterIsInstance<Segment.SlotRef>().firstOrNull { it.slot !in ids }?.let { throw malformed("segmento aponta lacuna inexistente ${it.slot}") }
            val conformance = o.list("conformance").map { raw ->
                val v = raw.asObj("vetor")
                ConformanceVector(
                    values = v["values"].asObj("values").mapValues { (k, x) -> x as? String ?: throw malformed("valor de $k não é texto") },
                    copies = v.int("copies"),
                    bytesSha256 = v.str("bytesSha256"),
                )
            }
            if (conformance.size < 2) throw malformed("menos de 2 vetores de conformidade")
            return PackageTarget(language, o.int("dpi"), segments, slots, conformance)
        }
    }
}

class ColetorPackageException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** packageSha256 não confere com os bytes. */
        HASH,
        /** Assinatura inválida ou de outra chave (kid). */
        SIGNATURE,
        /** JSON ou estrutura fora do contrato. */
        MALFORMED,
        /** format/formatVersion que este app não conhece. */
        FORMAT,
        /** Os bytes montados pelo app não batem com o vetor do PC. */
        CONFORMANCE,
    }
}

private fun malformed(why: String) = ColetorPackageException(ColetorPackageException.Reason.MALFORMED, "Pacote inválido: $why")

@Suppress("UNCHECKED_CAST")
private fun Any?.asObj(what: String): Map<String, Any?> = this as? Map<String, Any?> ?: throw malformed("$what não é objeto")
private fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: throw malformed("'$k' ausente ou não é texto")
private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: throw malformed("'$k' ausente ou não é booleano")
private fun Map<String, Any?>.num(k: String): Double = (this[k] as? Number)?.toDouble() ?: throw malformed("'$k' ausente ou não é número")
private fun Map<String, Any?>.int(k: String): Int {
    val v = this[k] as? Long ?: throw malformed("'$k' ausente ou não é inteiro")
    if (v !in Int.MIN_VALUE..Int.MAX_VALUE) throw malformed("'$k' fora do intervalo")
    return v.toInt()
}
private fun Map<String, Any?>.list(k: String): List<Any?> = this[k] as? List<Any?> ?: throw malformed("'$k' ausente ou não é lista")
private inline fun <reified E : Enum<E>> Map<String, Any?>.enum(k: String): E {
    val v = str(k)
    return enumValues<E>().firstOrNull { it.name == v } ?: throw malformed("'$k' = '$v' desconhecido")
}
