package br.com.atos.printbridge.data.coletor

import android.os.Bundle
import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lê a ligação com o PC das etiquetas da configuração gerenciada do MDM (`coletor_provisioning`:
 * o JSON inteiro devolvido por POST /api/coletor/devices no PC). Chamado pelo
 * ProvisioningCoordinator na abertura e a cada mudança da configuração, como o license_token.
 *
 * A chave pública que assina os pacotes chega SÓ por este canal (MDM, confiável), nunca pela rede
 * da loja. Chave ausente ou vazia não apaga a ligação atual (revogar é no PC, que responde 401).
 * O token nunca vai para log.
 */
@Singleton
class ManagedColetorLink @Inject constructor(
    private val store: AndroidColetorStore,
) {
    enum class Result { NO_CONFIG, UNCHANGED, STORED, INVALID }

    fun syncFromRestrictions(restrictions: Bundle?): Result {
        val raw = restrictions?.getString(RESTRICTION_KEY)?.trim()
        if (raw.isNullOrEmpty()) return Result.NO_CONFIG
        return apply(raw)
    }

    fun apply(provisioningJson: String): Result {
        val link = try {
            ColetorLink.parse(provisioningJson)
        } catch (e: ColetorLink.InvalidException) {
            Logger.event(LogLevel.WARN, "coletor_link_invalid", extra = mapOf("error" to e.message))
            return Result.INVALID
        }
        if (store.linkFingerprint() == link.fingerprint()) return Result.UNCHANGED
        store.saveLink(link)
        Logger.event(LogLevel.INFO, "coletor_link_stored", extra = mapOf("deviceId" to link.deviceId, "server" to link.serverUrl, "kid" to link.kid))
        return Result.STORED
    }

    companion object {
        const val RESTRICTION_KEY = "coletor_provisioning"
    }
}
