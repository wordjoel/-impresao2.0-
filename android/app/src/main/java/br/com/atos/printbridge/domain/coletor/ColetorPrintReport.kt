package br.com.atos.printbridge.domain.coletor

/**
 * Relatório de impressão para o PC (POST /coletor/v1/print-reports, schema PrintReport do
 * contrato). Nasce PENDENTE quando o job entra na fila e fica pronto quando a fila dá o job como
 * enviado ou falho. Best-effort: nunca segura nem atrasa a impressão.
 */
data class ColetorPrintReport(
    val reportId: String,
    val jobId: String,
    val templateId: String,
    val templateVersion: Int,
    val packageSha256: String,
    val language: String,
    val dpi: Int,
    val printerName: String,
    val printerModel: String?,
    val versionCheck: String,
    val labelSha256: String,
    val createdAtMillis: Long,
    /** null = job ainda na fila. */
    val status: Status? = null,
    val errorMessage: String? = null,
    /** Quando a fila terminou o job (ISO-8601 UTC), vira o `at` do contrato. */
    val finishedAt: String? = null,
) {
    enum class Status { SENT, FAILED }

    val ready: Boolean get() = status != null

    /** Corpo do POST, só com os campos do contrato. */
    fun toContractJson(): String = buildString {
        append('{')
        field("reportId", reportId).append(',')
        field("templateId", templateId).append(',')
        append("\"templateVersion\":").append(templateVersion).append(',')
        field("packageSha256", packageSha256).append(',')
        append("\"target\":{\"language\":").append(q(language)).append(",\"dpi\":").append(dpi).append("},")
        append("\"printer\":{\"name\":").append(q(printerName))
        if (printerModel != null) append(",\"model\":").append(q(printerModel))
        append("},")
        append("\"operator\":null,")
        field("status", requireNotNull(status) { "relatório ainda pendente" }.name).append(',')
        append("\"errorMessage\":").append(errorMessage?.let(::q) ?: "null").append(',')
        field("versionCheck", versionCheck).append(',')
        append("\"labels\":[{\"bytesSha256\":").append(q(labelSha256)).append("}],")
        field("at", requireNotNull(finishedAt))
        append('}')
    }

    /** Formato de armazenamento local (inclui o que o contrato não leva: jobId, pendência). */
    fun toStorageJson(): String = buildString {
        append('{')
        field("reportId", reportId).append(',')
        field("jobId", jobId).append(',')
        field("templateId", templateId).append(',')
        append("\"templateVersion\":").append(templateVersion).append(',')
        field("packageSha256", packageSha256).append(',')
        field("language", language).append(',')
        append("\"dpi\":").append(dpi).append(',')
        field("printerName", printerName).append(',')
        append("\"printerModel\":").append(printerModel?.let(::q) ?: "null").append(',')
        field("versionCheck", versionCheck).append(',')
        field("labelSha256", labelSha256).append(',')
        append("\"createdAtMillis\":").append(createdAtMillis).append(',')
        append("\"status\":").append(status?.name?.let(::q) ?: "null").append(',')
        append("\"errorMessage\":").append(errorMessage?.let(::q) ?: "null").append(',')
        append("\"finishedAt\":").append(finishedAt?.let(::q) ?: "null")
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String): StringBuilder = append(q(name)).append(':').append(q(value))

    companion object {
        fun fromStorageJson(json: String): ColetorPrintReport? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val o = StrictJson.parse(json) as Map<String, Any?>
            ColetorPrintReport(
                reportId = o["reportId"] as String,
                jobId = o["jobId"] as String,
                templateId = o["templateId"] as String,
                templateVersion = (o["templateVersion"] as Long).toInt(),
                packageSha256 = o["packageSha256"] as String,
                language = o["language"] as String,
                dpi = (o["dpi"] as Long).toInt(),
                printerName = o["printerName"] as String,
                printerModel = o["printerModel"] as String?,
                versionCheck = o["versionCheck"] as String,
                labelSha256 = o["labelSha256"] as String,
                createdAtMillis = o["createdAtMillis"] as Long,
                status = (o["status"] as String?)?.let { Status.valueOf(it) },
                errorMessage = o["errorMessage"] as String?,
                finishedAt = o["finishedAt"] as String?,
            )
        }.getOrNull()

        /** String JSON com escape (RFC 8259). */
        fun q(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c.code < 0x20 || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
