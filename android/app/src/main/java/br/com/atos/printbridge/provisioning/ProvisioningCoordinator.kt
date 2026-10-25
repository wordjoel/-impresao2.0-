package br.com.atos.printbridge.provisioning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import androidx.core.content.ContextCompat
import br.com.atos.printbridge.application.provisioning.ApplyProvisioningUseCase
import br.com.atos.printbridge.data.coletor.ManagedColetorLink
import br.com.atos.printbridge.domain.provisioning.ProvisioningConfig
import br.com.atos.printbridge.license.ManagedLicenseLink
import br.com.atos.printbridge.license.ManagedStatusReporter
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instalação em escala: aplica a configuração da loja sem ninguém tocar no coletor.
 *
 * Origens, em ordem de autoridade:
 *  1. Managed Configurations do MDM/EMM — autoridade em produção. Lidas ao iniciar o app e de novo
 *     a cada ACTION_APPLICATION_RESTRICTIONS_CHANGED (o MDM mudou algo).
 *  2. provisioning.json na pasta do app (Android/data/<pacote>/files/), empurrado por ADB na
 *     bancada de preparação — usado só se o MDM não entregou configuração. Depois de lido é
 *     renomeado para provisioning.applied.json, e o resultado vai para provisioning-result.txt,
 *     que o script da bancada lê de volta.
 *
 * Só reaplica quando a configuração muda (impressão digital guardada localmente).
 */
@Singleton
class ProvisioningCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applyProvisioning: ApplyProvisioningUseCase,
    // D2: a mesma configuração gerenciada traz o License Token (license_token) do agente oficial.
    private val managedLicense: ManagedLicenseLink,
    // Passo 6: e a ligação com o PC das etiquetas (coletor_provisioning).
    private val managedColetor: ManagedColetorLink,
    // Item 2: com MDM presente (há configuração gerenciada), o estado da impressão vai para ele.
    private val statusReporter: ManagedStatusReporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences("provisioning", Context.MODE_PRIVATE) }

    private val restrictionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Logger.event(LogLevel.INFO, "managed_config_changed")
            scope.launch { applyPending() }
        }
    }

    fun start() {
        // Só pode ser registrado em runtime (o sistema não entrega esta ação a receiver de manifesto).
        ContextCompat.registerReceiver(
            context,
            restrictionsChanged,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { applyPending() }
    }

    suspend fun applyPending() = mutex.withLock {
        // Licença primeiro e sempre: um bundle só com license_token conta como "sem configuração
        // de loja" (ProvisioningConfig.isEmpty) e não pode deixar o token sem leitura.
        val restrictions = readRestrictions()
        managedLicense.syncFromRestrictions(restrictions)
        managedColetor.syncFromRestrictions(restrictions)
        if (restrictions != null && !restrictions.isEmpty) statusReporter.start()
        val managed = readManagedConfig()
        if (managed != null && !managed.isEmpty) {
            applyIfChanged(managed, SOURCE_MDM)
            return@withLock
        }
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return@withLock
        val resultFile = File(file.parentFile, RESULT_FILE_NAME)
        val summary = try {
            val config = ProvisioningReaders.fromJson(file.readText())
            // Arquivo da bancada é sempre aplicado (é um pedido explícito), mesmo se igual ao anterior.
            val result = applyProvisioning(config, SOURCE_FILE)
            if (result.applied) prefs.edit().putString(KEY_FINGERPRINT, config.fingerprint()).apply()
            (if (result.applied) "OK " else "ERRO ") + result.summary
        } catch (e: Exception) {
            Logger.event(LogLevel.WARN, "provisioning_file_invalid", extra = mapOf("error" to (e.message ?: e::class.simpleName)))
            "ERRO provisioning.json inválido: ${e.message}"
        }
        resultFile.writeText(summary + "\n")
        file.renameTo(File(file.parentFile, APPLIED_FILE_NAME))
    }

    private suspend fun applyIfChanged(config: ProvisioningConfig, source: String) {
        val fingerprint = config.fingerprint()
        if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint) return
        val result = applyProvisioning(config, source)
        if (result.applied) prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
    }

    private fun readRestrictions(): android.os.Bundle? = try {
        (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager).applicationRestrictions
    } catch (e: Exception) {
        Logger.event(LogLevel.WARN, "managed_config_unreadable", extra = mapOf("error" to (e.message ?: e::class.simpleName)))
        null
    }

    private fun readManagedConfig(): ProvisioningConfig? = try {
        val manager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        ProvisioningReaders.fromManagedConfig(manager.applicationRestrictions)
    } catch (e: Exception) {
        Logger.event(LogLevel.WARN, "managed_config_unreadable", extra = mapOf("error" to (e.message ?: e::class.simpleName)))
        null
    }

    companion object {
        const val FILE_NAME = "provisioning.json"
        const val APPLIED_FILE_NAME = "provisioning.applied.json"
        const val RESULT_FILE_NAME = "provisioning-result.txt"
        private const val KEY_FINGERPRINT = "fingerprint"
        const val SOURCE_MDM = "mdm"
        const val SOURCE_FILE = "arquivo"
    }
}
