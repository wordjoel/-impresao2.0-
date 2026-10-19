package br.com.atos.printbridge.domain.model

/**
 * Estado operacional da loja no app — derivado localmente (Fase 23 — Status da Loja). UNKNOWN é o
 * estado inicial local (nenhum device avaliado ainda); ONLINE/DEGRADED/OFFLINE passam a ser
 * derivados localmente quando a avaliação de saúde existir (Fase 23).
 * Mantido por consistência com o contrato alvo.
 */
enum class StoreStatus {
    UNKNOWN,
    ONLINE,
    DEGRADED,
    OFFLINE,
}

/**
 * Estado de ativação da loja. Espelha o ciclo de vida de licença por loja (Fase 18 —
 * StoreLicense) e os estados definidos em `CARREFOUR-PRINT-SERVER-LICENSING.md` §3, de forma que o
 * app local consiga rastrear a ativação antes mesmo de existir backend real.
 */
enum class StoreActivationStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    EXPIRED,
    REVOKED,
}

/**
 * Loja (unidade operacional) — raiz do novo modelo por loja (Fase 2 do fluxo Carrefour).
 * Uma instalação Android pertence a uma Store; uma Store agrega vários Devices e várias Printers.
 * Este modelo é a raiz de agregação: Device (Fase 3) e Printer (Fase 4) carregam storeId.
 */
data class Store(
    val storeId: String,
    val storeCode: String,
    val storeName: String,
    val status: StoreStatus,
    val activationStatus: StoreActivationStatus,
    val createdAt: Long,
    val updatedAt: Long,
)