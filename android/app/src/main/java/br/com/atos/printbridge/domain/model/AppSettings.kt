package br.com.atos.printbridge.domain.model

data class AppSettings(
    // 5 tentativas com backoff exponencial a partir de 2s (2, 4, 8, 16, 32s) — Fase 1, Etapa 2.
    val maxAttempts: Int = PrintJob.DEFAULT_MAX_ATTEMPTS,
    val connectTimeoutMillis: Int = 5_000,
    val socketTimeoutMillis: Int = 8_000,
    val retryBaseIntervalMillis: Long = 2_000,
    val autoRetryTransientFailures: Boolean = true,
    val historyRetentionDays: Int = 30,
    val adminPinHash: String? = null,
    // Porta RAW EXTRA sondada na busca, além das portas registradas (631 IPP, 9100 RAW, 515 LPD) —
    // para impressoras de etiqueta configuradas numa porta fora do padrão. Igual a 9100 = nenhuma extra.
    val discoveryPort: Int = 9100,
)
