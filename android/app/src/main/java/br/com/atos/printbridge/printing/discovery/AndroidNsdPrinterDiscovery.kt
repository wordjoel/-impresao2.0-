package br.com.atos.printbridge.printing.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import br.com.atos.printbridge.domain.discovery.MdnsPrinterDiscovery
import br.com.atos.printbridge.domain.discovery.MdnsServiceHit
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Descoberta mDNS/DNS-SD via Android NSD. Sob demanda: só existe enquanto o Flow for coletado;
 * encerrar chama stopServiceDiscovery e libera o lock multicast. Nunca envia payload de impressão.
 *
 * Os resolves são SERIALIZADOS (um por vez no app inteiro): até o Android 13 o NsdManager recusa
 * um segundo resolve simultâneo com FAILURE_ALREADY_ACTIVE, e com vários tipos de serviço em
 * paralelo isso perderia impressoras silenciosamente.
 */
@Singleton
class AndroidNsdPrinterDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) : MdnsPrinterDiscovery {

    private val resolveLock = Mutex()

    override fun discover(serviceType: String): Flow<MdnsServiceHit> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val multicastLock = acquireMulticastLock()
        var discoveryListener: NsdManager.DiscoveryListener? = null
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        if (nsdManager == null) {
            Logger.event(LogLevel.WARN, "printer_discovery_mdns_unavailable")
            close()
        } else {
            launch {
                for (info in found) {
                    val resolved = resolveLock.withLock { resolve(nsdManager, info) } ?: continue
                    val host = resolved.host ?: continue
                    val hit = MdnsServiceHit(
                        serviceType = serviceType,
                        serviceName = resolved.serviceName.orEmpty(),
                        hostAddress = host.hostAddress.orEmpty(),
                        hostName = runCatching { host.hostName }.getOrNull(),
                        port = resolved.port,
                        txt = resolved.attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() },
                    )
                    Logger.event(
                        LogLevel.INFO,
                        "printer_discovery_mdns_found",
                        extra = mapOf("type" to serviceType, "address" to hit.hostAddress, "port" to hit.port, "name" to hit.serviceName),
                    )
                    trySend(hit)
                }
            }
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    Logger.event(LogLevel.WARN, "printer_discovery_mdns_start_failed", extra = mapOf("serviceType" to type, "errorCode" to errorCode))
                    close()
                }

                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.trySend(serviceInfo) }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure {
                Logger.event(LogLevel.WARN, "printer_discovery_mdns_start_threw", extra = mapOf("exception" to (it.message ?: it::class.simpleName)))
                close()
            }
        }

        awaitClose {
            found.close()
            discoveryListener?.let { listener -> runCatching { nsdManager?.stopServiceDiscovery(listener) } }
            multicastLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        }
    }

    @Suppress("DEPRECATION") // resolveService: a API nova (registerServiceInfoCallback) só existe no Android 14+.
    private suspend fun resolve(nsdManager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Logger.event(LogLevel.WARN, "printer_discovery_mdns_resolve_failed", extra = mapOf("name" to serviceInfo.serviceName, "errorCode" to errorCode))
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                }
                runCatching { nsdManager.resolveService(info, listener) }.onFailure {
                    Logger.event(LogLevel.WARN, "printer_discovery_mdns_resolve_threw", extra = mapOf("exception" to (it.message ?: it::class.simpleName)))
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("printbridge-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private companion object {
        const val RESOLVE_TIMEOUT_MILLIS = 4_000L
    }
}
