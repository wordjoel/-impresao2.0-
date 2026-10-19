package br.com.atos.printbridge.domain.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN do modo administrador nunca é armazenado em texto puro — apenas "salt:hash" (SHA-256),
 * salt aleatório por configuração. Não é um cofre de segredos de alto valor (é um PIN local de
 * 4-6 dígitos para proteger configurações operacionais, não credenciais de sistema), então
 * SHA-256 com salt é proporcional à ameaça real (evitar leitura casual do valor em backup/log).
 */
object PinHasher {
    private const val ITERATIONS_SEPARATOR = ":"

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = sha256(salt + pin.toByteArray(Charsets.UTF_8))
        return "${salt.toHex()}$ITERATIONS_SEPARATOR${digest.toHex()}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(ITERATIONS_SEPARATOR)
        if (parts.size != 2) return false
        val salt = parts[0].hexToBytes()
        val expectedDigest = parts[1]
        val actualDigest = sha256(salt + pin.toByteArray(Charsets.UTF_8)).toHex()
        return constantTimeEquals(expectedDigest, actualDigest)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
