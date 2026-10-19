package br.com.atos.printbridge.license

import br.com.atos.printbridge.domain.license.LicenseTokenVerifier

/**
 * D2 (Fase D, 14/10/2026): regra pura para o License Token que chega pela configuração
 * gerenciada (chave [RESTRICTION_KEY]), gravada pelo agente oficial com.onmdm.agent (dono do
 * aparelho). O token só substitui o guardado se a assinatura confere e se ele é DESTE aparelho:
 * uma configuração corrompida ou de outro coletor nunca derruba uma licença boa.
 *
 * Status REVOKED/SUSPENDED/EXPIRED com assinatura válida É gravado: é assim que a revogação chega.
 * A avaliação de sempre (EvaluateLicenseStatusUseCase) decide se imprime.
 */
object ManagedLicenseTokenPolicy {
    const val RESTRICTION_KEY = "license_token"

    /** Chave do Keyed App State com o identificador de licença (lido pelo agente oficial). */
    const val LICENSE_DEVICE_ID_STATE_KEY = "license_device_id"

    enum class Decision { NO_TOKEN, UNCHANGED, INVALID_SIGNATURE, WRONG_DEVICE, STORE }

    fun decide(
        incoming: String?,
        stored: String?,
        verifier: LicenseTokenVerifier,
        localDeviceId: String?,
    ): Decision {
        val token = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return Decision.NO_TOKEN
        if (token == stored) return Decision.UNCHANGED
        val payload = verifier.verify(token) ?: return Decision.INVALID_SIGNATURE
        if (localDeviceId == null || payload.deviceId != localDeviceId) return Decision.WRONG_DEVICE
        return Decision.STORE
    }
}
