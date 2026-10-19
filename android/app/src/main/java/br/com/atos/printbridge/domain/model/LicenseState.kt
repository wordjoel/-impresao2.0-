package br.com.atos.printbridge.domain.model

/**
 * Estado de uma License no backend — CARREFOUR-PRINT-SERVER-LICENSING.md §3. Os cinco estados
 * exigidos; PENDING é o estado inicial (licença criada, nenhum device ativado ainda), REVOKED é
 * terminal (mesma regra de TENANTS.md/DEVICE-IDENTITY.md — reativar é sempre uma License nova).
 */
enum class LicenseTokenStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    EXPIRED,
    REVOKED,
}

/**
 * Conteúdo verificado de um License Token — só existe como esta classe depois de passar por
 * [br.com.atos.printbridge.domain.license.LicenseTokenVerifier.verify]. Nunca construído
 * diretamente a partir de dado não verificado (CARREFOUR-PRINT-SERVER-LICENSING.md §5).
 */
data class LicenseTokenPayload(
    val deviceId: String,
    val licenseId: String,
    val tenantId: String,
    val status: LicenseTokenStatus,
    val issuedAt: Long,
    val validUntil: Long,
    val maxOfflineMillis: Long,
)

/**
 * Estado local persistido — deliberadamente reduzido a UM campo. Antes desta versão, o estado
 * local carregava campos livres (status, lastValidatedAt) que o próprio app escrevia em si
 * mesmo — nada impedia editar o DataStore local para forjar "status=ACTIVE". Agora o único dado
 * persistido é o token assinado pelo servidor; todo o resto (status, validade) só existe depois
 * de verificado via [br.com.atos.printbridge.domain.license.LicenseTokenVerifier] — nunca lido de
 * um campo livre. Ver CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 */
data class LicenseState(
    /** Token compacto "payload.assinatura" (Base64Url), emitido na ativação e renovado a cada heartbeat. Null = nunca ativado. */
    val rawToken: String? = null,
)
