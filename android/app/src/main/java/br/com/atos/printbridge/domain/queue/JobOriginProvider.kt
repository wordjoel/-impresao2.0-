package br.com.atos.printbridge.domain.queue

/**
 * Identidade do coletor gravada em cada job (PROMPT MASTER §24). O `storeId` vem da impressora
 * de destino; aqui ficam o aparelho (chave do Keystore) e o coletor da Central de Tokens.
 */
interface JobOriginProvider {
    fun deviceId(): String?

    /** Posição "loja × coletor" da Central de Tokens; nulo enquanto o aparelho não foi vinculado. */
    fun collectorId(): String?

    /** Sem identidade (testes que não tratam de origem). */
    object None : JobOriginProvider {
        override fun deviceId(): String? = null
        override fun collectorId(): String? = null
    }
}

/** Origem conhecida de quem pediu a impressão, gravada em `sourceApp`. */
object JobSource {
    const val APP_LABELS = "app:etiquetas"
    const val APP_TEST_PAGE = "app:teste"
    const val SHARE = "android:compartilhar"
    const val ANDROID_PRINT = "android:imprimir"
    const val REPRINT = "app:reimpressao"

    /** App externo pela API local (AIDL ou Intent): o pacote de quem chamou. */
    fun externalApp(packageName: String?) = packageName?.takeIf { it.isNotBlank() }?.let { "api:$it" } ?: "api:desconhecido"
}
