package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveredService
import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.DiscoverySource
import br.com.atos.printbridge.domain.discovery.HostnameResolver
import br.com.atos.printbridge.domain.discovery.MdnsPrinterDiscovery
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.discovery.PrinterDiscovery
import br.com.atos.printbridge.domain.discovery.SubnetScanner
import br.com.atos.printbridge.domain.ipp.IppEndpoint
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppGateway
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.snmp.SnmpIdentityProbe
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Orquestra a descoberta sob demanda:
 *  1. mDNS/DNS-SD (_ipp, _ipps, _pdl-datastream, _printer) e varredura TCP (631, 9100, 515) em
 *     paralelo — cada achado vira/atualiza um host;
 *  2. cada serviço IPP é VERIFICADO com Get-Printer-Attributes (identidade + capacidades, sem
 *     imprimir nada); cada host novo é consultado por SNMP e DNS reverso.
 * Emite o estado atual do host a cada mudança. Tudo dentro do orçamento de tempo da rodada;
 * cancelar a coleta cancela fontes e identificação. Nunca envia job de impressão.
 */
class PrinterDiscoveryServiceImpl @Inject constructor(
    private val mdns: MdnsPrinterDiscovery,
    private val subnetScanner: SubnetScanner,
    private val networkStateProvider: NetworkStateProvider,
    private val ippGateway: IppGateway,
    private val snmpProbe: SnmpIdentityProbe,
    private val hostnameResolver: HostnameResolver,
) : PrinterDiscovery {

    override fun discover(request: DiscoveryRequest): Flow<DiscoveredPrinter> = channelFlow {
        if (!networkStateProvider.isNetworkAvailable()) {
            Logger.event(LogLevel.WARN, "printer_discovery_no_network")
            return@channelFlow
        }
        Logger.event(
            LogLevel.INFO,
            "printer_discovery_started",
            extra = mapOf("ports" to request.ports.joinToString(","), "budgetMs" to request.totalDurationMillis),
        )

        val round = Round(this, request)
        val sources = buildList {
            PrintServiceProtocol.entries.forEach { protocol ->
                add(launch { mdns.discover(protocol.mdnsType).collect { hit -> round.onMdns(hit) } })
            }
            add(launch { subnetScanner.scan(request).collect { open -> round.onOpenPort(open.ipAddress, open.port) } })
        }

        withTimeoutOrNull(request.totalDurationMillis) {
            sources.joinAll()
            round.awaitIdentification()
        }
        coroutineContext.cancelChildren()
        Logger.event(LogLevel.INFO, "printer_discovery_finished", extra = mapOf("hosts" to round.hostCount()))
    }

    /** Estado de uma rodada: hosts por IP, e o que já foi disparado para não repetir consultas. */
    private inner class Round(private val scope: ProducerScope<DiscoveredPrinter>, private val request: DiscoveryRequest) {
        private val hosts = HashMap<String, DiscoveredPrinter>()
        private val lock = Mutex()
        private val ippTried = ConcurrentHashMap.newKeySet<String>()
        private val hostTried = ConcurrentHashMap.newKeySet<String>()
        private val identification = Collections.synchronizedList(mutableListOf<Job>())

        suspend fun hostCount() = lock.withLock { hosts.size }

        suspend fun update(ip: String, transform: (DiscoveredPrinter?) -> DiscoveredPrinter?) {
            val snapshot = lock.withLock {
                val next = transform(hosts[ip]) ?: return@withLock null
                hosts[ip] = next
                next
            } ?: return
            scope.send(snapshot)
        }

        suspend fun onMdns(hit: br.com.atos.printbridge.domain.discovery.MdnsServiceHit) {
            update(hit.hostAddress) { NsdServiceMapper.apply(it, hit) }
            val protocol = PrintServiceProtocol.ofMdnsType(hit.serviceType) ?: return
            onHost(hit.hostAddress)
            if (protocol == PrintServiceProtocol.IPP) {
                val port = if (hit.port in 1..65535) hit.port else protocol.ianaPort
                val rp = hit.txt.entries.firstOrNull { it.key.equals("rp", true) }?.value
                onIpp(hit.hostAddress, port, rp)
            }
        }

        suspend fun onOpenPort(ip: String, port: Int) {
            // Porta fora das registradas só é sondada quando configurada como RAW extra.
            val protocol = PrintServiceProtocol.ofScannedPort(port) ?: PrintServiceProtocol.RAW
            val note = when (protocol) {
                PrintServiceProtocol.IPP -> "Porta $port aberta; verificando o serviço IPP"
                PrintServiceProtocol.RAW -> "Porta $port aberta (RAW); linguagem da impressora não identificada"
                PrintServiceProtocol.LPD -> "Porta $port aberta (LPD); o Print Bridge não envia por LPD"
                PrintServiceProtocol.IPPS -> null
            }
            update(ip) { (it ?: DiscoveredPrinter(ip)).withService(DiscoveredService(protocol, port, sources = setOf(DiscoverySource.SUBNET_SCAN), note = note)) }
            onHost(ip)
            if (protocol == PrintServiceProtocol.IPP) onIpp(ip, port, null)
        }

        private fun onHost(ip: String) {
            if (!hostTried.add(ip)) return
            identification += scope.launch {
                val name = hostnameResolver.resolve(ip, request.identifyTimeoutMillis)
                if (name != null) update(ip) { it?.copy(hostname = it.hostname ?: name) }
            }
            identification += scope.launch {
                val snmp = snmpProbe.identify(ip, request.identifyTimeoutMillis) ?: return@launch
                Logger.event(LogLevel.INFO, "printer_discovery_snmp", extra = mapOf("address" to ip, "descr" to (snmp.bestDescription ?: "")))
                update(ip) { current ->
                    current ?: return@update null
                    if (current.identitySource == NsdServiceMapper.SOURCE_IPP) return@update current
                    val gaveIdentity = snmp.manufacturer != null || snmp.model != null
                    current.copy(
                        manufacturer = current.manufacturer ?: snmp.manufacturer,
                        model = current.model ?: snmp.model,
                        name = current.name ?: snmp.sysName,
                        identitySource = current.identitySource ?: NsdServiceMapper.SOURCE_SNMP.takeIf { gaveIdentity },
                    )
                }
            }
        }

        private fun onIpp(ip: String, port: Int, hintPath: String?) {
            if (!ippTried.add("$ip:$port")) return
            identification += scope.launch { verifyIpp(ip, port, hintPath) }
        }

        private suspend fun verifyIpp(ip: String, port: Int, hintPath: String?) {
            val paths = (listOfNotNull(hintPath?.trim()?.trimStart('/')) + IppGateway.CANDIDATE_PATHS).distinct()
            var lastFailure: IppResult.Failure? = null
            for (path in paths) {
                when (val result = ippGateway.getPrinterAttributes(IppEndpoint(ip, port, path), request.identifyTimeoutMillis)) {
                    is IppResult.Ok -> {
                        val d = result.value
                        Logger.event(
                            LogLevel.INFO,
                            "printer_discovery_ipp_verified",
                            extra = mapOf("address" to "$ip:$port/$path", "makeAndModel" to (d.makeAndModel ?: ""), "formats" to d.capabilities.documentFormats.joinToString(",")),
                        )
                        update(ip) { current ->
                            (current ?: DiscoveredPrinter(ip))
                                .withService(
                                    DiscoveredService(
                                        PrintServiceProtocol.IPP, port, resourcePath = path,
                                        sources = emptySet(), verified = true,
                                        note = "Respondeu Get-Printer-Attributes em /$path",
                                    ),
                                )
                                .copy(
                                    manufacturer = d.manufacturer ?: current?.manufacturer,
                                    model = d.model ?: current?.model,
                                    name = current?.name ?: d.name ?: d.info,
                                    identitySource = NsdServiceMapper.SOURCE_IPP,
                                    capabilities = d.capabilities,
                                    state = d.stateLabel,
                                    stateReasons = d.problems,
                                )
                        }
                        return
                    }
                    is IppResult.Failure -> {
                        lastFailure = result
                        // Host inacessível não melhora trocando de caminho.
                        if (result.kind == IppFailureKind.UNREACHABLE || result.kind == IppFailureKind.TIMEOUT) break
                    }
                }
            }
            val detail = lastFailure?.technicalMessage ?: "sem resposta"
            update(ip) { current ->
                (current ?: DiscoveredPrinter(ip)).withService(
                    DiscoveredService(
                        PrintServiceProtocol.IPP, port, sources = emptySet(), verified = false,
                        note = "Porta $port: o serviço IPP não respondeu ($detail)",
                    ),
                )
            }
        }

        suspend fun awaitIdentification() {
            while (true) {
                val pending = synchronized(identification) { identification.filter { it.isActive } }
                if (pending.isEmpty()) return
                pending.joinAll()
            }
        }
    }
}
