package br.com.atos.printbridge.domain.license

import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Interoperabilidade com o backend real: o token abaixo foi EMITIDO pelo gateway MDM
 * (mdm-device-gateway, _shared/licenseToken.ts, WebCrypto P1363 -> DER) no stack local, assinado
 * com uma chave descartavel de teste (Gate 11 do MDM-ON-02). Se o formato do servidor e o
 * verificador do app divergirem (campos, Base64Url ou codificacao da assinatura), este teste quebra.
 */
class MdmGatewayLicenseTokenInteropTest {

    private val gatewayTestPublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHxUrsxcCTrq4v+pnrfwPQRnvxJxbXNsRJQCJk82d7wGvac/F1b7qgExIRrNHpRk+8q/s/zz+TcUxwXphDC70zw=="

    private val gatewayIssuedToken =
        "YTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMWExYTFhMXxjNDMzZTBjYy05NTJjLTQ4YWEtOGU4ZS0wMDk0Y2JjYmI3MzJ8N2Y4YTY4MzItMzNhMS00M2JmLWJkOGItYTc1NzNiYzMyM2YxfEFDVElWRXwxNzkxMjA4MDc4NzE1fDE3OTE0NjcyNzg3MTV8MjU5MjAwMDAw.MEUCIQC9xZmLg-7ZuONZdQtMRfOiM2aTBz4ge0WCMoj_Zng0OQIgIWJ__I5mv4qQ8jlbGbm73Zggn6qgA2g7vZaQ3ByW8vQ"

    private val verifier = EcdsaLicenseTokenVerifier(Base64.getDecoder().decode(gatewayTestPublicKey))

    @Test
    fun `token emitido pelo gateway MDM verifica no app com todos os campos`() {
        val payload = verifier.verify(gatewayIssuedToken)
        assertNotNull(payload)
        assertEquals("a1".repeat(32), payload!!.deviceId)
        assertEquals(LicenseTokenStatus.ACTIVE, payload.status)
        assertEquals(259_200_000L, payload.maxOfflineMillis)
        assertEquals(payload.issuedAt + payload.maxOfflineMillis, payload.validUntil)
    }

    @Test
    fun `token do gateway com payload adulterado e recusado`() {
        val (payload, signature) = gatewayIssuedToken.split(".")
        val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(decoded.replace("|ACTIVE|", "|REVOKED|").toByteArray(Charsets.UTF_8))
        assertNull(verifier.verify("$forged.$signature"))
    }
}
