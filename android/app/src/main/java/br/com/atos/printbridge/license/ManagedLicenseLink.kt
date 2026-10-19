package br.com.atos.printbridge.license

import android.content.Context
import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppState
import androidx.enterprise.feedback.KeyedAppStatesReporter
import androidx.work.WorkManager
import br.com.atos.printbridge.application.usecase.StoreLicenseTokenUseCase
import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.license.LicenseTokenVerifier
import br.com.atos.printbridge.domain.repository.LicenseRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D2: ligação do app com o agente oficial com.onmdm.agent (dono do aparelho), sem agente
 * embutido. Duas mãos, ambas por mecanismos do Android Enterprise:
 *
 *  - app -> agente: publica o identificador de licença (SHA-256 da chave PÚBLICA do Keystore)
 *    como Keyed App State (androidx.enterprise:enterprise-feedback). A biblioteca só entrega ao
 *    dono/perfil do aparelho (e à Play Store); o agente identifica o remetente pelo UID do sistema.
 *  - agente -> app: o agente grava o License Token na configuração gerenciada (license_token);
 *    o ProvisioningCoordinator lê na abertura e a cada ACTION_APPLICATION_RESTRICTIONS_CHANGED
 *    e chama [syncFromRestrictions].
 *
 * Nada aqui faz rede nem fica residente. O token nunca vai para log.
 */
@Singleton
class ManagedLicenseLink @Inject constructor(
    @ApplicationContext private val context: Context,
    // Sob demanda (dagger.Lazy): o Android Keystore so abre quando o identificador e pedido,
    // nunca na abertura do app (mesma regra da antiga VerifiedLicenseBridge).
    private val deviceIdentity: dagger.Lazy<DeviceIdentityProvider>,
    private val verifier: LicenseTokenVerifier,
    private val licenseRepository: LicenseRepository,
    private val storeLicenseToken: StoreLicenseTokenUseCase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            cancelLegacyEmbeddedAgentWork()
            publishLicenseDeviceId()
        }
    }

    /**
     * Aparelhos que rodaram a versão com agente embutido têm o trabalho periódico dele agendado no
     * WorkManager. A classe saiu do app: cancela pelo nome único para não acordar o processo à toa.
     * Não apaga dados (preferências e banco ficam como estão).
     */
    private fun cancelLegacyEmbeddedAgentWork() {
        runCatching {
            val wm = WorkManager.getInstance(context)
            LEGACY_EMBEDDED_AGENT_WORK.forEach { wm.cancelUniqueWork(it) }
        }.onFailure {
            Logger.event(LogLevel.WARN, "legacy_agent_work_cancel_failed", extra = mapOf("error" to (it.message ?: it::class.simpleName)))
        }
    }

    private fun publishLicenseDeviceId() {
        val id = runCatching { deviceIdentity.get().deviceId() }.getOrNull() ?: run {
            Logger.event(LogLevel.WARN, "license_device_id_unavailable")
            return
        }
        runCatching {
            val state = KeyedAppState.builder()
                .setKey(ManagedLicenseTokenPolicy.LICENSE_DEVICE_ID_STATE_KEY)
                .setSeverity(KeyedAppState.SEVERITY_INFO)
                .setMessage("Identificador de licença do Print Bridge")
                .setData(id)
                .build()
            KeyedAppStatesReporter.create(context).setStatesImmediate(listOf(state))
        }.onSuccess {
            Logger.event(LogLevel.INFO, "license_device_id_published")
        }.onFailure {
            Logger.event(LogLevel.WARN, "license_device_id_publish_failed", extra = mapOf("error" to (it.message ?: it::class.simpleName)))
        }
    }

    /** Lê license_token da configuração gerenciada e grava só se for válido e deste aparelho. */
    suspend fun syncFromRestrictions(restrictions: Bundle?) {
        val incoming = restrictions?.getString(ManagedLicenseTokenPolicy.RESTRICTION_KEY)
        val stored = licenseRepository.current().rawToken
        val localId = runCatching { deviceIdentity.get().deviceId() }.getOrNull()
        val decision = ManagedLicenseTokenPolicy.decide(incoming, stored, verifier, localId)
        if (decision == ManagedLicenseTokenPolicy.Decision.STORE) storeLicenseToken(incoming!!.trim())
        if (decision != ManagedLicenseTokenPolicy.Decision.NO_TOKEN && decision != ManagedLicenseTokenPolicy.Decision.UNCHANGED) {
            Logger.event(LogLevel.INFO, "managed_license_token", extra = mapOf("decision" to decision.name))
        }
    }

    companion object {
        /** Nomes únicos usados pela versão com agente embutido (mdm/worker/MdmHeartbeatWorker). */
        val LEGACY_EMBEDDED_AGENT_WORK = listOf("mdm_heartbeat_periodic", "mdm_heartbeat_onetime")
    }
}
