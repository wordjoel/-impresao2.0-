package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.HostReachabilityProbe
import br.com.atos.printbridge.domain.discovery.Ipv4Subnet
import br.com.atos.printbridge.domain.discovery.LocalNetworkInfoProvider
import br.com.atos.printbridge.domain.discovery.OpenPort
import br.com.atos.printbridge.domain.discovery.SubnetScanner
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Varredura TCP da subnet local nas portas de impressão pedidas, sob demanda:
 * - hosts limitados a [DiscoveryRequest.maxHosts] (janela centrada no próprio device em subnets
 *   grandes — ver [Ipv4Subnet]);
 * - concorrência limitada a [DiscoveryRequest.maxConcurrency] (Semaphore), contando cada host:porta;
 * - timeout por sonda + margem, para nunca travar a rodada num host que não responde;
 * - só abre e fecha o socket — porta aberta NÃO é impressora confirmada; quem confirma é a
 *   identificação (IPP/SNMP) depois;
 * - cancelar a coleta encerra as sondas.
 */
class TcpSubnetScanner @Inject constructor(
    private val localNetworkInfoProvider: LocalNetworkInfoProvider,
    private val probe: HostReachabilityProbe,
) : SubnetScanner {

    override fun scan(request: DiscoveryRequest): Flow<OpenPort> = channelFlow {
        val local = localNetworkInfoProvider.current() ?: run {
            Logger.event(LogLevel.INFO, "printer_discovery_subnet_skipped", extra = mapOf("reason" to "no_local_ipv4"))
            return@channelFlow
        }

        val hosts = Ipv4Subnet
            .hostsToScan(local.address, local.prefixLength, request.maxHosts)
            .filterNot { it == local.address }
            .take(request.maxHosts)

        if (hosts.isEmpty() || request.ports.isEmpty()) {
            Logger.event(LogLevel.INFO, "printer_discovery_subnet_skipped", extra = mapOf("reason" to "empty_hosts_or_ports"))
            return@channelFlow
        }

        Logger.event(
            LogLevel.INFO,
            "printer_discovery_subnet_started",
            extra = mapOf(
                "local" to "${local.address}/${local.prefixLength}",
                "ports" to request.ports.joinToString(","),
                "hosts" to hosts.size,
                "concurrency" to request.maxConcurrency,
                "probeTimeoutMs" to request.probeTimeoutMillis,
            ),
        )

        val semaphore = Semaphore(request.maxConcurrency)
        val jobs = hosts.flatMap { host ->
            request.ports.map { port ->
                launch {
                    semaphore.withPermit {
                        // withTimeoutOrNull é rede de segurança acima do timeout do socket.
                        val open = withTimeoutOrNull(request.probeTimeoutMillis.toLong() + PROBE_GRACE_MILLIS) {
                            probe.probe(host, port, request.probeTimeoutMillis)
                        } ?: false
                        if (open) send(OpenPort(host, port))
                    }
                }
            }
        }
        jobs.joinAll()

        Logger.event(LogLevel.INFO, "printer_discovery_subnet_finished", extra = mapOf("hostsProbed" to hosts.size))
    }

    private companion object {
        const val PROBE_GRACE_MILLIS = 250L
    }
}
