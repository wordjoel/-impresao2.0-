package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import java.util.Base64
import javax.inject.Inject

/**
 * Implementa o passo "Device: assina challenge usando sua chave privada" do desenho de
 * challenge-response — CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md §1 e
 * CARREFOUR-PRINT-CROSS-PLATFORM.md §9. Orquestra apenas: decodifica o nonce (Base64, como chega
 * em HeartbeatChallenge.nonce), pede a assinatura a [DeviceIdentityProvider] (que nunca expõe a
 * chave privada, só a operação de assinar) e recodifica o resultado para o formato de fio
 * (Base64 DER — HeartbeatVerification.signature).
 *
 * Deliberadamente burro/fino: toda a segurança real está em [DeviceIdentityProvider] (a chave
 * nunca sai do Android Keystore) e no formato de assinatura já fixado (DER, não P1363) — este use
 * case não introduz nenhuma decisão criptográfica nova.
 */
class RespondToDeviceChallengeUseCase @Inject constructor(
    private val deviceIdentityProvider: DeviceIdentityProvider,
) {
    /** @param challengeBase64 O campo `nonce` recebido de POST /devices/{deviceId}/heartbeat/challenge. */
    operator fun invoke(challengeBase64: String): String {
        val challengeBytes = Base64.getDecoder().decode(challengeBase64)
        val signatureBytes = deviceIdentityProvider.sign(challengeBytes)
        return Base64.getEncoder().encodeToString(signatureBytes)
    }
}
