package br.com.atos.printbridge.domain.coletor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ColetorLinkTest {

    private val ecPem = pem(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)
    private val token = "Ab3_-".repeat(9) // 45 caracteres, base64url como o print-server gera

    private fun pem(der: ByteArray) = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END PUBLIC KEY-----\n"

    private fun json(url: String = "http://10.10.40.10:7172", tok: String = token, keyPem: String = ecPem, kid: String = "athos-label-0123456789abcdef") =
        """{"deviceId":"d-1","token":"$tok","serverUrl":"$url","signingKey":{"kid":"$kid","publicKeyPem":${quote(keyPem)}}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `le o provisionamento do print-server`() {
        val link = ColetorLink.parse(json())
        assertEquals("d-1", link.deviceId)
        assertEquals("10.10.40.10", link.host)
        assertEquals(7172, link.port)
        assertEquals("http://10.10.40.10:7172", link.serverUrl)
        assertEquals("athos-label-0123456789abcdef", link.kid)
        // toString e impressão digital nunca expõem o token.
        assertFalse(link.toString().contains(token))
        assertFalse(link.fingerprint().contains(token))
        assertNotEquals(link.fingerprint(), ColetorLink.parse(json(tok = "Z".repeat(45))).fingerprint())
    }

    @Test
    fun `so aceita http em IPv4 privado da loja`() {
        for (ok in listOf("http://10.0.0.1:1", "http://172.16.5.4:7172", "http://172.31.255.255:65535", "http://192.168.0.10:7172/")) {
            ColetorLink.parse(json(url = ok))
        }
        for (bad in listOf(
            "https://10.10.40.10:7172", "http://8.8.8.8:7172", "http://172.32.0.1:7172", "http://192.169.0.1:7172",
            "http://127.0.0.1:7172", "http://pc-loja:7172", "http://10.10.40.10", "http://10.10.40.10:0", "http://10.10.40.10:70000",
            "http://10.10.40.10:7172/coletor", "http://10.10.40.10:7172?x=1", "http://010.10.40.10:7172", "http://10.10.40.256:7172",
            "http://user@10.10.40.10:7172",
        )) {
            expectInvalid(json(url = bad), bad)
        }
    }

    @Test
    fun `recusa token, chave e JSON fora do contrato`() {
        expectInvalid(json(tok = "curto"), "token curto")
        expectInvalid(json(tok = "com espaco".padEnd(30, 'x')), "token com espaço")
        val rsa = pem(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded)
        expectInvalid(json(keyPem = rsa), "chave RSA")
        val p384 = pem(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded)
        expectInvalid(json(keyPem = p384), "chave P-384")
        expectInvalid(json(keyPem = "não é pem"), "pem inválido")
        expectInvalid(json(kid = ""), "kid vazio")
        expectInvalid("[]", "array")
        expectInvalid("{", "JSON quebrado")
    }

    @Test
    fun `parseServerUrl`() {
        assertEquals("192.168.1.2" to 7172, ColetorLink.parseServerUrl("http://192.168.1.2:7172"))
        assertNull(ColetorLink.parseServerUrl("http://1.2.3.4:7172"))
    }

    private fun expectInvalid(text: String, what: String) {
        try {
            ColetorLink.parse(text)
            fail("aceitou: $what")
        } catch (_: ColetorLink.InvalidException) {
        }
    }
}
