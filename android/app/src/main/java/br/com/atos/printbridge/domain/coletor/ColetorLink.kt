package br.com.atos.printbridge.domain.coletor

import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ligação do coletor com o PC das etiquetas: o DeviceProvisioning devolvido por
 * POST /api/coletor/devices (contrato designer-coletor.openapi.yaml), colado INTEIRO numa única
 * chave da configuração gerenciada do MDM (`coletor_provisioning`). O token só dá leitura de
 * pacotes + envio de relatório e é revogável no PC; nunca vai para log.
 */
data class ColetorLink(
    val deviceId: String,
    val token: String,
    /** http://<IP privado>:<porta> — validado em [parse]. */
    val host: String,
    val port: Int,
    val kid: String,
    /** Chave pública ES256 (SPKI DER) que assina os pacotes. Só chega por aqui, nunca pela rede. */
    val publicKeySpki: ByteArray,
) {
    val serverUrl: String get() = "http://$host:$port"

    /** Impressão digital para saber se o MDM mudou a ligação (sem o token em claro). */
    fun fingerprint(): String =
        ColetorPackageVerifier.sha256Hex("$deviceId|$host|$port|$kid|${Base64.getEncoder().encodeToString(publicKeySpki)}|$token".toByteArray()).take(16)

    override fun equals(other: Any?): Boolean =
        other is ColetorLink && deviceId == other.deviceId && token == other.token && host == other.host &&
            port == other.port && kid == other.kid && publicKeySpki.contentEquals(other.publicKeySpki)

    override fun hashCode(): Int = deviceId.hashCode()

    /** Nunca imprime o token. */
    override fun toString(): String = "ColetorLink(deviceId=$deviceId, server=$serverUrl, kid=$kid)"

    class InvalidException(message: String) : IllegalArgumentException(message)

    companion object {
        /**
         * Lê o JSON do provisionamento. Recusa qualquer coisa fora do contrato e, por segurança,
         * servidor que não seja HTTP num IPv4 PRIVADO da loja: o token trafega sem TLS no v1
         * (decisão P2), então nunca pode ir para um endereço de internet.
         */
        fun parse(json: String): ColetorLink {
            fun bad(why: String): Nothing = throw InvalidException("Provisionamento do coletor inválido: $why")
            @Suppress("UNCHECKED_CAST")
            val o = runCatching { StrictJson.parse(json.trim()) as? Map<String, Any?> }.getOrNull() ?: bad("não é um objeto JSON")
            val deviceId = (o["deviceId"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: bad("deviceId")
            val token = (o["token"] as? String)?.takeIf { it.length in 20..256 && it.none(Char::isWhitespace) } ?: bad("token")
            val url = o["serverUrl"] as? String ?: bad("serverUrl")
            val (host, port) = parseServerUrl(url) ?: bad("serverUrl precisa ser http://<IP privado da loja>:<porta> (ex.: http://10.10.40.10:7172)")
            @Suppress("UNCHECKED_CAST")
            val key = o["signingKey"] as? Map<String, Any?> ?: bad("signingKey")
            val kid = (key["kid"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: bad("signingKey.kid")
            val pem = key["publicKeyPem"] as? String ?: bad("signingKey.publicKeyPem")
            val spki = pemToSpki(pem) ?: bad("signingKey.publicKeyPem não é uma chave pública EC P-256")
            return ColetorLink(deviceId, token, host, port, kid, spki)
        }

        /** http://a.b.c.d:porta[/] com IPv4 privado (10/8, 172.16/12, 192.168/16). Nada de nome, caminho ou query. */
        fun parseServerUrl(url: String): Pair<String, Int>? {
            val m = Regex("^http://(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})/?$").matchEntire(url.trim()) ?: return null
            val octets = m.groupValues[1].split('.').map { it.toInt() }
            if (octets.any { it > 255 } || m.groupValues[1].split('.').any { it.length > 1 && it.startsWith('0') }) return null
            val port = m.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
            val private = octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
            return if (private) m.groupValues[1] to port else null
        }

        private fun pemToSpki(pem: String): ByteArray? {
            val body = pem.trim()
            if (!body.startsWith("-----BEGIN PUBLIC KEY-----") || !body.endsWith("-----END PUBLIC KEY-----")) return null
            val b64 = body.removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----").filterNot(Char::isWhitespace)
            val der = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) }.getOrNull() as? ECPublicKey ?: return null
            // P-256: campo de 256 bits.
            return der.takeIf { key.params.curve.field.fieldSize == 256 }
        }
    }
}
