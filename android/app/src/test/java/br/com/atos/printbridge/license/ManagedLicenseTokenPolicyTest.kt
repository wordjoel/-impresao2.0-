package br.com.atos.printbridge.license

import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import br.com.atos.printbridge.license.ManagedLicenseTokenPolicy.Decision
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import org.junit.Assert.assertEquals
import org.junit.Test

/** UNITÁRIO: regra de aceitação do License Token vindo da configuração gerenciada (D2). */
class ManagedLicenseTokenPolicyTest {
    private val verifier = TestLicenseTokenSigner.createVerifier()
    private val me = TestLicenseTokenSigner.DEFAULT_DEVICE_ID

    @Test
    fun `sem token na configuracao nao faz nada`() {
        assertEquals(Decision.NO_TOKEN, ManagedLicenseTokenPolicy.decide(null, null, verifier, me))
        assertEquals(Decision.NO_TOKEN, ManagedLicenseTokenPolicy.decide("   ", "guardado", verifier, me))
    }

    @Test
    fun `token valido deste aparelho e gravado, igual ao guardado nao regrava`() {
        val token = TestLicenseTokenSigner.sign()
        assertEquals(Decision.STORE, ManagedLicenseTokenPolicy.decide(token, null, verifier, me))
        assertEquals(Decision.UNCHANGED, ManagedLicenseTokenPolicy.decide(token, token, verifier, me))
    }

    @Test
    fun `assinatura invalida ou token corrompido nunca substitui o guardado`() {
        val good = TestLicenseTokenSigner.sign()
        val (payload, signature) = good.split(".")
        val tampered = payload.dropLast(2) + "AA." + signature
        assertEquals(Decision.INVALID_SIGNATURE, ManagedLicenseTokenPolicy.decide(tampered, good, verifier, me))
        assertEquals(Decision.INVALID_SIGNATURE, ManagedLicenseTokenPolicy.decide("lixo", good, verifier, me))
    }

    @Test
    fun `token de outro aparelho ou sem identidade local e recusado`() {
        val other = TestLicenseTokenSigner.sign(deviceId = "outro-coletor")
        assertEquals(Decision.WRONG_DEVICE, ManagedLicenseTokenPolicy.decide(other, null, verifier, me))
        assertEquals(Decision.WRONG_DEVICE, ManagedLicenseTokenPolicy.decide(TestLicenseTokenSigner.sign(), null, verifier, null))
    }

    @Test
    fun `revogacao com assinatura valida e gravada - e assim que ela chega`() {
        val revoked = TestLicenseTokenSigner.sign(status = LicenseTokenStatus.REVOKED)
        assertEquals(Decision.STORE, ManagedLicenseTokenPolicy.decide(revoked, TestLicenseTokenSigner.sign(), verifier, me))
    }
}
