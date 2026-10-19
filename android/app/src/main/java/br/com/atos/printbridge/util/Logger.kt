package br.com.atos.printbridge.util

import android.util.Log

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Logging estruturado (JSON por linha) em vez de mensagens soltas — cada evento carrega
 * contexto de correlação (jobId/printerId) para permitir reconstruir o histórico de um job
 * a partir do logcat/exportação de diagnóstico. Nunca inclua PIN, payload ZPL completo ou
 * outros dados sensíveis nos campos de evento.
 *
 * Serialização manual (sem org.json.JSONObject) de propósito: em testes JVM puro (sem
 * Robolectric), a implementação stub de org.json vinda do android.jar devolve null em
 * toString(), quebrando qualquer use case que loga algo. Uma pequena serialização própria
 * evita essa dependência do runtime Android e mantém domain/application testáveis sem emulador.
 */
object Logger {
    private const val TAG = "PrintBridge"
    private val ringBuffer = ArrayDeque<String>()
    private const val RING_BUFFER_CAPACITY = 500

    /**
     * Salvaguarda de código, não só de convenção de comentário (CARREFOUR-PRINT-DATA-CLASSIFICATION.md §4):
     * qualquer chave de `extra` cujo nome bata com este padrão tem o VALOR redigido antes de
     * entrar no ring buffer — ou seja, antes de poder ser exportado por DiagnosticsExporter ou
     * escrito no logcat. Protege contra um `extra = mapOf("licenseToken" to token)` adicionado
     * por engano no futuro, sem depender de ninguém lembrar da regra em code review.
     */
    private val SENSITIVE_KEY_PATTERN = Regex(
        "token|senha|password|secret|credential|adminpin|licensetoken|devicetoken|rawtoken",
        RegexOption.IGNORE_CASE,
    )

    fun event(
        level: LogLevel,
        event: String,
        jobId: String? = null,
        printerId: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val fields = LinkedHashMap<String, Any?>()
        fields["ts"] = System.currentTimeMillis()
        fields["level"] = level.name
        fields["event"] = event
        jobId?.let { fields["jobId"] = it }
        printerId?.let { fields["printerId"] = it }
        extra.forEach { (key, value) ->
            fields[key] = if (SENSITIVE_KEY_PATTERN.containsMatchIn(key)) "[REDACTED]" else value
        }

        val line = fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k)}\":${renderValue(v)}"
        }

        synchronized(ringBuffer) {
            if (ringBuffer.size >= RING_BUFFER_CAPACITY) ringBuffer.removeFirst()
            ringBuffer.addLast(line)
        }

        runCatching {
            when (level) {
                LogLevel.DEBUG -> Log.d(TAG, line)
                LogLevel.INFO -> Log.i(TAG, line)
                LogLevel.WARN -> Log.w(TAG, line)
                LogLevel.ERROR -> Log.e(TAG, line)
            }
        }
    }

    fun recentEvents(): List<String> = synchronized(ringBuffer) { ringBuffer.toList() }

    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
