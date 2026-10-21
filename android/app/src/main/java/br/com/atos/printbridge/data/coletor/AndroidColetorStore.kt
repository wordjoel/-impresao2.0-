package br.com.atos.printbridge.data.coletor

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import br.com.atos.printbridge.application.coletor.ColetorLocalStore
import br.com.atos.printbridge.application.coletor.ColetorTemplateResolver
import br.com.atos.printbridge.application.coletor.DeviceClock
import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Cifra do token do coletor. Produção: [KeystoreSecretBox] (chave AES no Android Keystore, não exportável). */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/**
 * Guarda a ligação com o PC e os pacotes validados no armazenamento PRIVADO do app:
 *  - SharedPreferences "coletor": ligação (sem o token em claro: o token vai cifrado pelo [SecretBox])
 *    e a última conferência bem-sucedida de cada template;
 *  - files/coletor/packages/<templateId>.json: o envelope como veio do PC (é revalidado a cada leitura).
 */
@Singleton
class AndroidColetorStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretBox,
) : ColetorLocalStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val dir by lazy { File(context.filesDir, "coletor/packages").apply { mkdirs() } }

    override fun link(): ColetorLink? {
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return null
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1).takeIf { it in 1..65535 } ?: return null
        val kid = prefs.getString(KEY_KID, null) ?: return null
        val spki = prefs.getString(KEY_SPKI, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        // Token ilegível (chave do Keystore perdida, dado mexido): sem ligação até o MDM reenviar.
        val token = prefs.getString(KEY_TOKEN, null)?.let(secrets::open) ?: return null
        // Revalida o endereço guardado com a mesma regra do provisionamento (só IP privado).
        if (ColetorLink.parseServerUrl("http://$host:$port") == null) return null
        return ColetorLink(deviceId, token, host, port, kid, spki)
    }

    /** Grava a ligação vinda do MDM (já validada por ColetorLink.parse). Trocou de PC ou de chave: os pacotes antigos saem. */
    fun saveLink(link: ColetorLink) {
        val previous = link()
        prefs.edit()
            .putString(KEY_DEVICE, link.deviceId)
            .putString(KEY_HOST, link.host)
            .putInt(KEY_PORT, link.port)
            .putString(KEY_KID, link.kid)
            .putString(KEY_SPKI, Base64.getEncoder().encodeToString(link.publicKeySpki))
            .putString(KEY_TOKEN, secrets.seal(link.token))
            .apply()
        if (previous != null && (previous.host != link.host || previous.port != link.port || previous.kid != link.kid)) clearPackages()
    }

    fun linkFingerprint(): String? = link()?.fingerprint()

    private fun file(templateId: String): File {
        require(ColetorTemplateResolver.SAFE_ID.matches(templateId)) { "templateId inválido" }
        return File(dir, "$templateId.json")
    }

    override fun envelope(templateId: String): String? = file(templateId).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun saveEnvelope(templateId: String, json: String) {
        val target = file(templateId)
        val tmp = File(dir, "$templateId.json.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "não deu para gravar o pacote $templateId" }
        }
    }

    override fun deleteEnvelope(templateId: String) {
        file(templateId).delete()
        prefs.edit().remove(KEY_CONF + templateId).apply()
    }

    /** Ids dos templates do designer guardados (para a tela e diagnóstico). */
    fun storedTemplateIds(): List<String> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.sorted()

    private fun clearPackages() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(KEY_CONF) }.forEach(::remove) }.apply()
    }

    override fun confirmation(templateId: String): ColetorPrintPolicy.Confirmation? {
        val parts = prefs.getString(KEY_CONF + templateId, null)?.split('|') ?: return null
        if (parts.size != 3) return null
        val elapsed = parts[1].toLongOrNull() ?: return null
        return ColetorPrintPolicy.Confirmation(parts[0], elapsed, parts[2])
    }

    override fun saveConfirmation(templateId: String, confirmation: ColetorPrintPolicy.Confirmation) {
        prefs.edit().putString(KEY_CONF + templateId, "${confirmation.packageSha256}|${confirmation.elapsedRealtimeMs}|${confirmation.bootId}").apply()
    }

    private companion object {
        const val PREFS = "coletor"
        const val KEY_DEVICE = "link_device_id"
        const val KEY_HOST = "link_host"
        const val KEY_PORT = "link_port"
        const val KEY_KID = "link_kid"
        const val KEY_SPKI = "link_public_key_spki"
        const val KEY_TOKEN = "link_token_sealed"
        const val KEY_CONF = "conf_"
    }
}

/**
 * Token do coletor cifrado com AES-256-GCM, chave gerada DENTRO do Android Keystore e nunca
 * exportável (como a identidade do aparelho, AndroidKeystoreDeviceIdentityProvider). Validação
 * real só em aparelho: o provider AndroidKeyStore não existe em teste de JVM.
 */
@Singleton
class KeystoreSecretBox @Inject constructor() : SecretBox {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ct)
    }

    override fun open(sealed: String): String? = runCatching {
        val all = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_BYTES)) }
        String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "athos_coletor_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Relógio monotônico + contagem de boots (Settings.Global.BOOT_COUNT, API 24+). */
@Singleton
class AndroidDeviceClock @Inject constructor(@ApplicationContext private val context: Context) : DeviceClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootId(): String =
        // Sem BOOT_COUNT (aparelho estranho), um id que nunca bate: a janela offline não vale, nunca o contrário.
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }.getOrDefault("desconhecido-${SystemClock.elapsedRealtime()}")
}
