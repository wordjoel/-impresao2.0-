package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.HostnameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import javax.inject.Inject

/** DNS reverso do IP. Se o nome devolvido for o próprio IP (sem registro PTR), não há hostname. */
class AndroidHostnameResolver @Inject constructor() : HostnameResolver {

    /**
     * Escopo solto de propósito: canonicalHostName bloqueia sem timeout próprio e não é
     * interrompível; como filho do chamador, o timeout abaixo esperaria ele terminar mesmo assim.
     */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun resolve(ipAddress: String, timeoutMillis: Int): String? {
        val lookup = lookupScope.async { runCatching { InetAddress.getByName(ipAddress).canonicalHostName }.getOrNull() }
        return withTimeoutOrNull(timeoutMillis.toLong()) { lookup.await() }
            ?.takeUnless { it.isBlank() || it == ipAddress }
    }
}
