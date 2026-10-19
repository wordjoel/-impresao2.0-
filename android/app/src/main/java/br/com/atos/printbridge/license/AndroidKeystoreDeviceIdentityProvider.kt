package br.com.atos.printbridge.license

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ver CARREFOUR-PRINT-SECURITY-MODEL.md §2.1. A chave privada é gerada dentro do Android
 * Keystore (TEE/StrongBox quando o hardware suporta) com `PURPOSE_SIGN` e nunca é exportável —
 * só a operação de assinatura é exposta, via [sign]. `deviceId` é derivado deterministicamente
 * da chave pública (SHA-256), então não precisa de armazenamento próprio: enquanto a chave
 * existir no Keystore, o deviceId é sempre o mesmo. Isso não é testável com JUnit/Robolectric
 * (o provider "AndroidKeyStore" depende de hardware/TEE real) — validação real fica pendente
 * de execução em dispositivo/emulador físico, mesma limitação já registrada em VALIDATE.md.
 */
@Singleton
class AndroidKeystoreDeviceIdentityProvider @Inject constructor() : DeviceIdentityProvider {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun deviceId(): String = sha256Hex(publicKey())

    override fun publicKey(): ByteArray {
        ensureKeyExists()
        return keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
    }

    override fun sign(challenge: ByteArray): ByteArray {
        ensureKeyExists()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(challenge)
        }.sign()
    }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "carrefour_print_bridge_device_identity"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
