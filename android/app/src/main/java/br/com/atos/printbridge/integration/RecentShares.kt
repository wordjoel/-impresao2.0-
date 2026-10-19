package br.com.atos.printbridge.integration

/**
 * Evita imprimir duas vezes o mesmo arquivo quando o operador toca "Compartilhar → A.print.Bridge"
 * de novo por não ver resposta imediata. Vale só dentro da janela; depois disso o mesmo arquivo
 * pode ser reenviado de propósito. Em memória do processo: se o app morrer, não bloqueia nada.
 */
object RecentShares {
    const val WINDOW_MILLIS = 15_000L

    private val lastSeen = HashMap<String, Long>()

    /** true = pode imprimir (e registra); false = o mesmo arquivo foi aceito há menos de [WINDOW_MILLIS]. */
    @Synchronized
    fun claim(key: String, now: Long): Boolean {
        lastSeen.entries.removeAll { now - it.value >= WINDOW_MILLIS }
        if (key in lastSeen) return false
        lastSeen[key] = now
        return true
    }

    @Synchronized
    internal fun clear() = lastSeen.clear()
}
