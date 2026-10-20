package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.coletor.ColetorPackageException.Reason
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Pacote aceito: o envelope original (para guardar e reenviar o ETag) e o pacote lido. */
data class VerifiedPackage(val packageJson: String, val packageSha256: String, val pkg: LabelPackage)

/**
 * Confere um envelope baixado do PC antes de guardar, na ordem do contrato: SHA-256 dos bytes de
 * packageJson → assinatura ES256 com a chave do PROVISIONAMENTO (nunca uma baixada da rede) →
 * leitura → vetores de conformidade (o app monta cada vetor e compara com o hash do PC). Qualquer
 * falha: [ColetorPackageException] e o pacote é descartado.
 *
 * JDK puro (como [br.com.atos.printbridge.domain.license.EcdsaLicenseTokenVerifier]): verificar
 * com chave pública não precisa de hardware seguro, e assim roda em teste de JVM.
 */
class ColetorPackageVerifier(private val kid: String, publicKeySpki: ByteArray) {

    private val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))

    fun verify(envelopeJson: String): VerifiedPackage {
        @Suppress("UNCHECKED_CAST")
        val env = runCatching { StrictJson.parse(envelopeJson) as? Map<String, Any?> }.getOrNull()
            ?: throw ColetorPackageException(Reason.MALFORMED, "Envelope não é um objeto JSON")
        val packageJson = env["packageJson"] as? String ?: throw ColetorPackageException(Reason.MALFORMED, "Envelope sem packageJson")
        val declared = env["packageSha256"] as? String ?: throw ColetorPackageException(Reason.MALFORMED, "Envelope sem packageSha256")
        @Suppress("UNCHECKED_CAST")
        val sig = env["signature"] as? Map<String, Any?> ?: throw ColetorPackageException(Reason.MALFORMED, "Envelope sem assinatura")

        val bytes = packageJson.toByteArray(Charsets.UTF_8)
        val actual = sha256Hex(bytes)
        if (actual != declared) throw ColetorPackageException(Reason.HASH, "SHA-256 do pacote não confere")

        if (sig["alg"] != "ES256") throw ColetorPackageException(Reason.SIGNATURE, "Algoritmo de assinatura '${sig["alg"]}' não aceito")
        if (sig["kid"] != kid) throw ColetorPackageException(Reason.SIGNATURE, "Pacote assinado por outra chave (${sig["kid"]}); refaça o provisionamento")
        val signature = runCatching { Base64.getDecoder().decode(sig["value"] as String) }.getOrNull()
            ?: throw ColetorPackageException(Reason.SIGNATURE, "Assinatura ilegível")
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(bytes)
            }.verify(signature)
        }.getOrDefault(false)
        if (!ok) throw ColetorPackageException(Reason.SIGNATURE, "Assinatura inválida: pacote adulterado ou de outro PC")

        val pkg = try {
            LabelPackage.parse(packageJson)
        } catch (e: StrictJson.JsonException) {
            throw ColetorPackageException(Reason.MALFORMED, e.message ?: "JSON inválido")
        }
        checkConformance(pkg)
        return VerifiedPackage(packageJson, actual, pkg)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Prova no aparelho que este app monta exatamente os bytes do PC, alvo por alvo. */
        fun checkConformance(pkg: LabelPackage) {
            for (t in pkg.targets) {
                for ((i, v) in t.conformance.withIndex()) {
                    val got = runCatching { sha256Hex(ColetorAssembler.assemble(t, v.values, v.copies)) }.getOrElse { e ->
                        throw ColetorPackageException(Reason.CONFORMANCE, "Vetor ${i + 1} de ${t.language}/${t.dpi} recusado pelo app: ${e.message}")
                    }
                    if (got != v.bytesSha256) {
                        throw ColetorPackageException(Reason.CONFORMANCE, "App incompatível com este pacote (${t.language}/${t.dpi}, vetor ${i + 1}); nada foi impresso")
                    }
                }
            }
        }
    }
}
