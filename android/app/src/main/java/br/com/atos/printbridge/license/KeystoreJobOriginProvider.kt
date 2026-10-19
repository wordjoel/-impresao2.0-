package br.com.atos.printbridge.license

import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.queue.JobOriginProvider
import javax.inject.Inject

/**
 * Origem do job a partir da identidade do Keystore. `collectorId` fica nulo até a ativação pela
 * Central de Tokens gravar o vínculo loja × coletor neste aparelho.
 */
class KeystoreJobOriginProvider @Inject constructor(
    private val deviceIdentity: DeviceIdentityProvider,
) : JobOriginProvider {
    // Falhar ao ler o Keystore nunca pode impedir uma impressão: o job sai sem deviceId.
    override fun deviceId(): String? = runCatching { deviceIdentity.deviceId() }.getOrNull()

    override fun collectorId(): String? = null
}
