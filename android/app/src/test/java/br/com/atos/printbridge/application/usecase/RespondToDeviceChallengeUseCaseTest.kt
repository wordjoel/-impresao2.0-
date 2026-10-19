package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.testutil.FakeDeviceIdentityProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Testa a orquestração (decodifica/recodifica Base64, repassa bytes) com um fake, e o passo
 * "servidor verifica usando chave pública registrada" de ponta a ponta com um par de chaves EC
 * real (java.security puro — não precisa do Android Keystore para provar a propriedade
 * criptográfica de assinatura/verificação em si, só para proteger a chave privada em produção).
 */
class RespondToDeviceChallengeUseCaseTest {

    @Test
    fun `respond passes the decoded challenge bytes to the identity provider`() {
        val identityProvider = FakeDeviceIdentityProvider()
        val useCase = RespondToDeviceChallengeUseCase(identityProvider)
        val challengeBytes = "um-nonce-de-32-bytes-do-servidor".toByteArray()
        val challengeBase64 = Base64.getEncoder().encodeToString(challengeBytes)

        useCase(challengeBase64)

        assertArrayEquals(challengeBytes, identityProvider.lastSignedChallenge)
    }

    @Test
    fun `respond returns the signature base64 encoded`() {
        val identityProvider = FakeDeviceIdentityProvider()
        val useCase = RespondToDeviceChallengeUseCase(identityProvider)
        val challengeBytes = "abc".toByteArray()
        val challengeBase64 = Base64.getEncoder().encodeToString(challengeBytes)

        val responseBase64 = useCase(challengeBase64)

        val expectedSignature = identityProvider.sign(challengeBytes)
        assertEquals(Base64.getEncoder().encodeToString(expectedSignature), responseBase64)
    }

    @Test
    fun `different challenges produce different responses`() {
        val identityProvider = FakeDeviceIdentityProvider()
        val useCase = RespondToDeviceChallengeUseCase(identityProvider)

        val response1 = useCase(Base64.getEncoder().encodeToString("challenge-um".toByteArray()))
        val response2 = useCase(Base64.getEncoder().encodeToString("challenge-dois".toByteArray()))

        assertNotEquals(response1, response2)
    }

    @Test
    fun `response to a server issued challenge verifies against the device public key`() {
        // Ponta a ponta com um par de chaves EC real (não o Android Keystore, que exige
        // instrumentação — ver WindowsCngDeviceIdentityProviderTests para o equivalente com
        // hardware real no Windows). Prova a mesma propriedade: assinar com a privada, verificar
        // com a pública, formato DER.
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val identityProvider = object : br.com.atos.printbridge.domain.license.DeviceIdentityProvider {
            override fun deviceId() = "test-device"
            override fun publicKey() = keyPair.public.encoded
            override fun sign(challenge: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(keyPair.private)
                    update(challenge)
                }.sign()
        }
        val useCase = RespondToDeviceChallengeUseCase(identityProvider)

        val serverNonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val responseBase64 = useCase(Base64.getEncoder().encodeToString(serverNonce))
        val signatureBytes = Base64.getDecoder().decode(responseBase64)

        val verified = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(serverNonce)
        }.verify(signatureBytes)

        org.junit.Assert.assertTrue(verified)
        assertEquals(0x30, signatureBytes[0].toInt()) // DER SEQUENCE, nunca raw P1363
    }

    @Test
    fun `a response signed for one challenge does not verify against a different challenge`() {
        // Prova a base do antirreplay: a assinatura é sobre o nonce específico — reapresentá-la
        // contra um nonce diferente (o que aconteceria numa tentativa de replay) falha.
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val originalNonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(originalNonce)
        }.sign()

        val differentNonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val verified = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(differentNonce)
        }.verify(signature)

        org.junit.Assert.assertFalse(verified)
    }
}
