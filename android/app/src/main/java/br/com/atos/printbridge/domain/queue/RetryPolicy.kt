package br.com.atos.printbridge.domain.queue

/**
 * Backoff exponencial entre tentativas de envio (Fase 1 do Print Bridge, Etapa 2): com a base
 * padrão de 2s a sequência é 2s, 4s, 8s, 16s, 32s — o jeito de dar tempo a uma impressora que está
 * reiniciando ou a um Wi-Fi que está reconectando sem martelar o socket no mesmo ritmo.
 *
 * Substitui o backoff linear anterior (`base * tentativa`). Função pura, sem relógio nem corrotina,
 * para ser testável sem tempo virtual.
 */
object RetryPolicy {

    /** Teto de segurança: uma base mal configurada nunca faz o worker dormir mais que isto. */
    const val MAX_BACKOFF_MILLIS = 5 * 60 * 1000L

    /**
     * Espera antes da próxima tentativa, dado quantas tentativas JÁ falharam ([failedAttempts] ≥ 1).
     * Base ≤ 0 desliga a espera (usado em testes e para quem quiser reenvio imediato).
     */
    fun backoffMillis(baseMillis: Long, failedAttempts: Int): Long {
        if (baseMillis <= 0 || failedAttempts <= 0) return 0
        // shl limitado a 20 evita overflow de Long mesmo com maxAttempts absurdo vindo das configurações.
        val factor = 1L shl (failedAttempts - 1).coerceAtMost(20)
        return (baseMillis * factor).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
