package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.license.LicenseTokenVerifier
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Reescrito para o modelo de License Token assinado — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 * Usa o verificador REAL (EcdsaLicenseTokenVerifier) contra a chave pública de teste, e o
 * assinador real (TestLicenseTokenSigner) contra a chave privada de teste correspondente — não
 * fakes, porque o próprio propósito desta funcionalidade é a assinatura criptográfica real.
 */
class LicenseUseCasesTest {

    private val verifier: LicenseTokenVerifier = TestLicenseTokenSigner.createVerifier()
    private val useCase = EvaluateLicenseStatusUseCase(FakeLicenseRepository(), verifier, TestLicenseTokenSigner.deviceIdentity())

    @Test
    fun `not activated device is reported as not activated regardless of time`() {
        val state = LicenseState(rawToken = null)
        assertEquals(EffectiveLicenseStatus.NOT_ACTIVATED, useCase.evaluate(state, verifier, nowMillis = 0L))
    }

    @Test
    fun `active token within validity window is operating`() {
        val token = TestLicenseTokenSigner.sign(status = LicenseTokenStatus.ACTIVE, issuedAt = 1000, validUntil = 2000)
        val state = LicenseState(rawToken = token)
        assertEquals(EffectiveLicenseStatus.OPERATING, useCase.evaluate(state, verifier, nowMillis = 1500))
    }

    @Test
    fun `active token past its validUntil is restricted`() {
        // A tolerância offline agora é decidida pelo servidor via validUntil, não por uma
        // constante do cliente — este teste prova exatamente esse limite.
        val token = TestLicenseTokenSigner.sign(status = LicenseTokenStatus.ACTIVE, issuedAt = 1000, validUntil = 2000)
        val state = LicenseState(rawToken = token)
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 2001))
    }

    @Test
    fun `pending status is restricted`() {
        assertNonActiveIsRestricted(LicenseTokenStatus.PENDING)
    }

    @Test
    fun `suspended status is restricted`() {
        assertNonActiveIsRestricted(LicenseTokenStatus.SUSPENDED)
    }

    @Test
    fun `expired status is restricted`() {
        assertNonActiveIsRestricted(LicenseTokenStatus.EXPIRED)
    }

    @Test
    fun `revoked status is restricted`() {
        assertNonActiveIsRestricted(LicenseTokenStatus.REVOKED)
    }

    private fun assertNonActiveIsRestricted(status: LicenseTokenStatus) {
        val token = TestLicenseTokenSigner.sign(status = status, issuedAt = 1000, validUntil = 999_999_999)
        val state = LicenseState(rawToken = token)
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 1500))
    }

    @Test
    fun `tampered payload is restricted not trusted as active`() {
        // Prova a regra central do pedido: nada armazenado localmente é confiável por si só.
        // Aqui simulamos alguém editando o DataStore local para tentar forjar status=ACTIVE — a
        // assinatura, calculada sobre o payload original, deixa de bater com o payload adulterado.
        val validToken = TestLicenseTokenSigner.sign(status = LicenseTokenStatus.SUSPENDED, issuedAt = 1000, validUntil = 999_999_999)
        val parts = validToken.split(".")
        val forgedPayload = "test-device|lic-test|tenant-test|ACTIVE|1000|999999999|259200000"
            .toByteArray(Charsets.UTF_8)
        val forgedPayloadB64 = Base64.getEncoder().encodeToString(forgedPayload).replace('+', '-').replace('/', '_').trimEnd('=')
        val tamperedToken = "$forgedPayloadB64.${parts[1]}" // assinatura antiga, payload novo forjado

        val state = LicenseState(rawToken = tamperedToken)
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 1500))
    }

    @Test
    fun `malformed token is restricted`() {
        val state = LicenseState(rawToken = "isto-nao-e-um-token-valido")
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 1500))
    }

    @Test
    fun `token signed by a different key is restricted`() {
        // Simula um token forjado por alguém que não é o servidor real — assina com QUALQUER
        // outra chave, nunca a de teste embutida no verificador.
        val otherKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payloadBytes = "test-device|lic-test|tenant-test|ACTIVE|1000|999999999|259200000".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(otherKeyPair.private)
            update(payloadBytes)
        }.sign()

        fun b64Url(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
        val forgedToken = "${b64Url(payloadBytes)}.${b64Url(signature)}"

        val state = LicenseState(rawToken = forgedToken)
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 1500))
    }

    @Test
    fun `invoke reads current state from repository`() = runTest {
        val token = TestLicenseTokenSigner.sign(status = LicenseTokenStatus.ACTIVE, issuedAt = 100, validUntil = 200)
        val repository = FakeLicenseRepository(LicenseState(rawToken = token))
        val evaluate = EvaluateLicenseStatusUseCase(repository, verifier, TestLicenseTokenSigner.deviceIdentity())
        assertEquals(EffectiveLicenseStatus.OPERATING, evaluate(nowMillis = 150))
    }

    // ── Anti-clonagem: DEVICE A + TOKEN X = autorizado; DEVICE B + TOKEN X = negado ──────────

    @Test
    fun `token issued for this device operates`() = runTest {
        val tokenX = TestLicenseTokenSigner.sign(deviceId = "device-a", issuedAt = 100, validUntil = 200)
        val deviceA = EvaluateLicenseStatusUseCase(
            FakeLicenseRepository(LicenseState(rawToken = tokenX)), verifier, TestLicenseTokenSigner.deviceIdentity("device-a"),
        )
        assertEquals(EffectiveLicenseStatus.OPERATING, deviceA(nowMillis = 150))
    }

    @Test
    fun `same valid token copied to another device is refused as wrong device`() = runTest {
        val tokenX = TestLicenseTokenSigner.sign(deviceId = "device-a", issuedAt = 100, validUntil = 200)
        val deviceB = EvaluateLicenseStatusUseCase(
            FakeLicenseRepository(LicenseState(rawToken = tokenX)), verifier, TestLicenseTokenSigner.deviceIdentity("device-b"),
        )
        assertEquals(EffectiveLicenseStatus.WRONG_DEVICE, deviceB(nowMillis = 150))
    }

    @Test
    fun `payload edited to this device id breaks the signature and stays restricted`() {
        // Clonar "trocando o deviceId no token" também não passa: a assinatura cobre o deviceId.
        val tokenX = TestLicenseTokenSigner.sign(deviceId = "device-a", issuedAt = 1000, validUntil = 999_999_999)
        val forged = "device-b|lic-test|tenant-test|ACTIVE|1000|999999999|259200000".toByteArray(Charsets.UTF_8)
        val forgedB64 = Base64.getEncoder().encodeToString(forged).replace('+', '-').replace('/', '_').trimEnd('=')
        val state = LicenseState(rawToken = "$forgedB64.${tokenX.split(".")[1]}")
        assertEquals(EffectiveLicenseStatus.RESTRICTED, useCase.evaluate(state, verifier, nowMillis = 1500, localDeviceId = "device-b"))
    }

    @Test
    fun `wrong device blocks new print jobs like any non operating license`() = runTest {
        val tokenX = TestLicenseTokenSigner.sign(deviceId = "device-a", validUntil = Long.MAX_VALUE / 2)
        val enqueue = EnqueuePrintJobUseCase(
            FakePrintJobRepository(),
            FakePrinterRepository(), // a licença é checada antes da impressora
            DuplicateSubmissionGuard(),
            FakeQueueDispatcher(),
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = tokenX)), verifier, TestLicenseTokenSigner.deviceIdentity("device-b"),
            ),
            FakeSettingsRepository(),
        )
        val error = runCatching { enqueue(null, PrintPayloadType.ZPL, "^XA^XZ", "clone") }.exceptionOrNull()
        assertTrue(error is LicenseRestrictedException)
    }

    @Test
    fun `store license token use case persists the new token`() = runTest {
        val repository = FakeLicenseRepository()
        val token = TestLicenseTokenSigner.sign()
        StoreLicenseTokenUseCase(repository)(token)
        assertEquals(token, repository.current().rawToken)
    }
}
