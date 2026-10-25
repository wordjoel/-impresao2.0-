package br.com.atos.printbridge.domain.status

import org.json.JSONArray
import org.json.JSONObject

/**
 * Estado da impressão que o app informa ao MDM (item 2 do controle pelo MDM). Vai ao agente
 * oficial como Keyed App State [PrintStatusEncoder.STATE_KEY]; o agente repassa ao gateway.
 *
 * Tudo vem do resultado REAL dos envios: o app não testa a conexão em segundo plano. E `SENT` é
 * "enviado à impressora", nunca "impresso" — os nomes aqui seguem isso.
 */
data class PrintStatusSnapshot(
    val generatedAt: Long,
    val pending: Int,
    val processing: Int,
    /** Idade do job pendente mais antigo, em segundos; null = fila vazia. */
    val oldestPendingAgeSeconds: Long?,
    val failedLast24h: Int,
    val sentLast24h: Int,
    val printers: List<PrinterStatus>,
)

data class PrinterStatus(
    val name: String,
    val address: String,
    val enabled: Boolean,
    val isDefault: Boolean,
    val activeJobs: Int,
    val lastSentAt: Long?,
    val lastFailedAt: Long?,
    val lastError: String?,
) {
    /** Pelo último resultado conhecido: falha depois do último envio OK = falhando. */
    val state: PrinterState
        get() = when {
            !enabled -> PrinterState.DISABLED
            lastFailedAt != null && (lastSentAt == null || lastFailedAt > lastSentAt) -> PrinterState.FAILING
            lastSentAt != null -> PrinterState.OK
            else -> PrinterState.NO_ACTIVITY
        }
}

enum class PrinterState(val code: String) {
    OK("OK"),
    FAILING("FAIL"),
    NO_ACTIVITY("IDLE"),
    DISABLED("OFF"),
}

enum class PrintStatusSeverity { INFO, ERROR }

/**
 * JSON compacto e estável (v1). O Keyed App State aceita no máximo 1000 caracteres em `data`:
 * as impressoras com problema vão primeiro e, se não couberem todas, `more` diz quantas ficaram
 * de fora. Textos livres (nome, erro) são cortados.
 */
object PrintStatusEncoder {
    const val STATE_KEY = "print_status"
    const val MAX_DATA_CHARS = 1000

    /** Fila parada há mais que isto (com job pendente) já é problema. */
    const val STUCK_QUEUE_SECONDS = 10 * 60L

    private const val MAX_NAME = 32
    private const val MAX_ERROR = 60

    fun severity(s: PrintStatusSnapshot): PrintStatusSeverity {
        val stuck = (s.oldestPendingAgeSeconds ?: 0) >= STUCK_QUEUE_SECONDS
        val failing = s.printers.any { it.state == PrinterState.FAILING }
        return if (stuck || failing) PrintStatusSeverity.ERROR else PrintStatusSeverity.INFO
    }

    fun encode(s: PrintStatusSnapshot): String {
        val ordered = s.printers.sortedWith(
            compareBy<PrinterStatus> { if (it.state == PrinterState.FAILING) 0 else 1 }
                .thenByDescending { it.activeJobs }
                .thenBy { it.name },
        )
        var included = ordered.size
        while (true) {
            val text = build(s, ordered.take(included), ordered.size - included)
            if (text.length <= MAX_DATA_CHARS || included == 0) return text
            included--
        }
    }

    private fun build(s: PrintStatusSnapshot, printers: List<PrinterStatus>, more: Int): String {
        val queue = JSONObject()
            .put("pend", s.pending)
            .put("proc", s.processing)
            .put("old", s.oldestPendingAgeSeconds ?: JSONObject.NULL)
            .put("fail24", s.failedLast24h)
            .put("sent24", s.sentLast24h)
        val list = JSONArray()
        printers.forEach { p ->
            list.put(
                JSONObject()
                    .put("n", p.name.trim().take(MAX_NAME))
                    .put("a", p.address.take(64))
                    .put("s", p.state.code)
                    .put("d", p.isDefault)
                    .put("q", p.activeJobs)
                    .put("ls", p.lastSentAt?.div(1000) ?: JSONObject.NULL)
                    .put("lf", p.lastFailedAt?.div(1000) ?: JSONObject.NULL)
                    .put("e", p.lastError?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_ERROR)?.ifEmpty { null } ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("v", 1)
            .put("t", s.generatedAt / 1000)
            .put("q", queue)
            .put("p", list)
            .put("more", more)
            .toString()
    }

    /**
     * Mesmo conteúdo, ignorando o horário e a idade exata da fila (que muda a cada segundo):
     * só importa se há fila e se ela passou do limite de parada. Evita republicar o que não mudou.
     */
    fun fingerprint(s: PrintStatusSnapshot): String {
        val ageBucket = s.oldestPendingAgeSeconds?.let { if (it >= STUCK_QUEUE_SECONDS) STUCK_QUEUE_SECONDS else 0L }
        return encode(s.copy(generatedAt = 0, oldestPendingAgeSeconds = ageBucket))
    }
}
