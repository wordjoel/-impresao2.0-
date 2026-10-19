package br.com.atos.printbridge.domain.license

import br.com.atos.printbridge.domain.model.LicenseTokenPayload
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Implementação de referência de [LicenseTokenVerifier] — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 * Usa apenas `java.security` (JDK puro, sem `android.*`) porque verificar uma assinatura com uma
 * chave PÚBLICA não precisa de hardware seguro — só o material privado (do lado do servidor,
 * nunca deste app) precisa de proteção especial. Por isso esta classe vive em `domain/`, testável
 * em JVM puro sem Robolectric — diferente de `AndroidKeystoreDeviceIdentityProvider`, que sim
 * precisa do Android Keystore porque ali é a chave PRIVADA do dispositivo que precisa de proteção.
 *
 * Formato do token: "{payloadBase64Url}.{assinaturaBase64Url}" — payload é a string canônica
 * "deviceId|licenseId|tenantId|status|issuedAt|validUntil|maxOfflineMillis" em UTF-8, assinada
 * com ECDSA P-256/SHA-256 em formato DER (mesmo formato usado em toda a malha de identidade de
 * dispositivo, CARREFOUR-PRINT-CROSS-PLATFORM.md §2 regra 4 — um único formato de assinatura em
 * todo o sistema).
 */
class EcdsaLicenseTokenVerifier(private val serverPublicKeySpki: ByteArray) : LicenseTokenVerifier {

    override fun verify(compactToken: String): LicenseTokenPayload? {
        val parts = compactToken.split(".")
        if (parts.size != 2) return null

        val payloadBytes = runCatching { base64UrlDecode(parts[0]) }.getOrNull() ?: return null
        val signatureBytes = runCatching { base64UrlDecode(parts[1]) }.getOrNull() ?: return null

        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(serverPublicKeySpki))
        }.getOrNull() ?: return null

        val verified = runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payloadBytes)
            }.verify(signatureBytes)
        }.getOrDefault(false)
        if (!verified) return null

        return parsePayload(payloadBytes)
    }

    private fun parsePayload(payloadBytes: ByteArray): LicenseTokenPayload? {
        val text = runCatching { payloadBytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val fields = text.split("|")
        if (fields.size != 7) return null

        val status = runCatching { LicenseTokenStatus.valueOf(fields[3].uppercase()) }.getOrNull() ?: return null
        val issuedAt = fields[4].toLongOrNull() ?: return null
        val validUntil = fields[5].toLongOrNull() ?: return null
        val maxOfflineMillis = fields[6].toLongOrNull() ?: return null

        return LicenseTokenPayload(
            deviceId = fields[0],
            licenseId = fields[1],
            tenantId = fields[2],
            status = status,
            issuedAt = issuedAt,
            validUntil = validUntil,
            maxOfflineMillis = maxOfflineMillis,
        )
    }

    private fun base64UrlDecode(value: String): ByteArray {
        var standard = value.replace('-', '+').replace('_', '/')
        val padding = (4 - standard.length % 4) % 4
        standard += "=".repeat(padding)
        return Base64.getDecoder().decode(standard)
    }
}
