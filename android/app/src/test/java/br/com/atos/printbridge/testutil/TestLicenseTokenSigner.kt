package br.com.atos.printbridge.testutil

import br.com.atos.printbridge.domain.license.EcdsaLicenseTokenVerifier
import br.com.atos.printbridge.domain.license.LicenseTokenVerifier
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Assina License Tokens de teste com a MESMA chave privada de teste usada para gerar o vetor de
 * CARREFOUR-PRINT-SERVER-LICENSING.md §6 (par gerado com OpenSSL, chave pública correspondente em
 * LicenseModule.TEST_SERVER_PUBLIC_KEY_SPKI_BASE64). Existe só em teste; nenhum código de
 * produção tem acesso a uma chave privada de servidor.
 */
object TestLicenseTokenSigner {

    // PKCS#8 DER da mesma chave privada EC de teste do vetor (convertida do PEM "EC PRIVATE KEY"
    // original via `openssl pkcs8 -topk8 -nocrypt`, formato que java.security.KeyFactory aceita
    // diretamente sem parser PEM/ASN.1 adicional).
    private const val TEST_SERVER_PRIVATE_KEY_PKCS8_BASE64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgyAA9rC7g3uznowGagIDl+GjfSLOj8eg8ogZXPgFt+EChRANCAARb72zP7LmVJV2IpuUcNOn3Da3inf86IYYo8v3ShRhNCn9ooESmK8l+DrnFqTX/G0jttcewT9eYNs4TvdWkaTV1"

    private const val TEST_SERVER_PUBLIC_KEY_SPKI_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/" +
            "aKBEpivJfg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ=="

    fun sign(
        deviceId: String = DEFAULT_DEVICE_ID,
        licenseId: String = "lic-test",
        tenantId: String = "tenant-test",
        status: LicenseTokenStatus = LicenseTokenStatus.ACTIVE,
        issuedAt: Long = 1_000_000L,
        validUntil: Long = 1_259_200_000L,
        maxOfflineMillis: Long = 72L * 60 * 60 * 1000,
    ): String {
        val canonical = "$deviceId|$licenseId|$tenantId|${status.name}|$issuedAt|$validUntil|$maxOfflineMillis"
        val payloadBytes = canonical.toByteArray(Charsets.UTF_8)

        val privateKeyBytes = Base64.getDecoder().decode(TEST_SERVER_PRIVATE_KEY_PKCS8_BASE64)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(payloadBytes)
        }.sign()

        return "${base64UrlEncode(payloadBytes)}.${base64UrlEncode(signature)}"
    }

    /** Verificador real (não fake) contra a mesma chave pública de teste. */
    fun createVerifier(): LicenseTokenVerifier =
        EcdsaLicenseTokenVerifier(Base64.getDecoder().decode(TEST_SERVER_PUBLIC_KEY_SPKI_BASE64))

    /** Aparelho para o qual [sign] emite por padrão — a licença só vale no aparelho do token. */
    const val DEFAULT_DEVICE_ID = "test-device"

    fun deviceIdentity(deviceId: String = DEFAULT_DEVICE_ID) = FakeDeviceIdentityProvider(deviceId)

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
}
