package br.com.atos.printbridge.domain.discovery

import br.com.atos.printbridge.domain.model.PrinterCapabilities
import kotlinx.coroutines.flow.Flow

enum class DiscoverySource(val label: String) { MDNS("mDNS/DNS-SD"), SUBNET_SCAN("varredura TCP") }

/**
 * Protocolos de impressão que a descoberta reconhece, com a porta registrada na IANA e o tipo de
 * serviço DNS-SD. A porta IANA só é SONDADA na varredura; um serviço só conta como utilizável
 * depois de responder no próprio protocolo (IPP responde Get-Printer-Attributes).
 */
enum class PrintServiceProtocol(val label: String, val supported: Boolean, val ianaPort: Int, val mdnsType: String) {
    IPP("IPP", supported = true, ianaPort = 631, mdnsType = "_ipp._tcp."),
    IPPS("IPPS (IPP sobre TLS)", supported = false, ianaPort = 631, mdnsType = "_ipps._tcp."),
    RAW("RAW / JetDirect", supported = true, ianaPort = 9100, mdnsType = "_pdl-datastream._tcp."),
    LPD("LPD", supported = false, ianaPort = 515, mdnsType = "_printer._tcp."),
    ;

    companion object {
        fun ofMdnsType(type: String): PrintServiceProtocol? {
            val normalized = type.trim().trimEnd('.').lowercase() + "."
            return entries.firstOrNull { it.mdnsType == normalized }
        }

        /** Portas sondadas na varredura TCP (IPPS divide a 631 com o IPP; a distinção vem do mDNS). */
        val SCAN_PORTS: List<Int> get() = listOf(IPP.ianaPort, RAW.ianaPort, LPD.ianaPort)

        fun ofScannedPort(port: Int): PrintServiceProtocol? = when (port) {
            IPP.ianaPort -> IPP
            RAW.ianaPort -> RAW
            LPD.ianaPort -> LPD
            else -> null
        }
    }
}

/**
 * Um serviço encontrado num host. [verified] = respondeu no próprio protocolo (IPP) ou foi
 * anunciado pela impressora via mDNS; porta aberta sozinha NÃO é verificação. [note] explica o
 * estado com a causa técnica, para a tela nunca esconder o motivo.
 */
data class DiscoveredService(
    val protocol: PrintServiceProtocol,
    val port: Int,
    val resourcePath: String? = null,
    val sources: Set<DiscoverySource>,
    val verified: Boolean = false,
    val note: String? = null,
) {
    val key: String get() = "${protocol.name}:$port"
    val usable: Boolean get() = protocol.supported && verified
}

/**
 * Um host da rede com os serviços de impressão encontrados. Identidade (fabricante, modelo,
 * nome) só vem preenchida quando uma fonte a informou — IPP, SNMP ou o TXT do mDNS — e
 * [identitySource] diz qual. Nada aqui é presumido.
 */
data class DiscoveredPrinter(
    val ipAddress: String,
    val hostname: String? = null,
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val identitySource: String? = null,
    val services: List<DiscoveredService> = emptyList(),
    val capabilities: PrinterCapabilities? = null,
    /** Estado declarado pela impressora (IPP printer-state), ex.: "Ociosa". */
    val state: String? = null,
    val stateReasons: List<String> = emptyList(),
) {
    val dedupeKey: String get() = ipAddress

    /** Serviço que o Print Bridge usaria: IPP verificado primeiro (documentos), depois RAW verificado. */
    val preferredService: DiscoveredService?
        get() = services.filter { it.usable }.minByOrNull { if (it.protocol == PrintServiceProtocol.IPP) 0 else 1 }

    /**
     * RAW com porta aberta mas sem confirmação: pode ser impressora de etiqueta (ZPL/TSPL) que só
     * fala socket — o cadastro manual escolhe a linguagem, marcado como configuração manual.
     */
    val unverifiedRaw: DiscoveredService?
        get() = services.firstOrNull { it.protocol == PrintServiceProtocol.RAW && !it.verified }

    /** Diagnóstico honesto quando não há serviço utilizável. Nulo = há um serviço utilizável. */
    val problem: String?
        get() = when {
            preferredService != null -> null
            services.isEmpty() -> "Host encontrado, mas nenhuma porta de impressão compatível foi identificada."
            services.any { it.protocol == PrintServiceProtocol.IPP && !it.verified } ->
                "Impressora encontrada, mas o serviço de impressão não respondeu."
            unverifiedRaw != null -> "Porta RAW aberta, mas a linguagem da impressora não foi identificada."
            services.all { !it.protocol.supported } ->
                "Impressora encontrada, porém protocolo não suportado (${services.joinToString { it.protocol.label }})."
            else -> "Serviço de impressão não confirmado."
        }

    fun withService(service: DiscoveredService): DiscoveredPrinter {
        val existing = services.firstOrNull { it.key == service.key }
        val merged = if (existing == null) {
            service
        } else {
            existing.copy(
                resourcePath = existing.resourcePath ?: service.resourcePath,
                sources = existing.sources + service.sources,
                verified = existing.verified || service.verified,
                // Nota mais recente vence, mas uma nota não verificada nunca apaga a de um serviço já verificado.
                note = if (existing.verified && !service.verified) existing.note ?: service.note else service.note ?: existing.note,
            )
        }
        return copy(services = services.filterNot { it.key == service.key } + merged)
    }
}

/**
 * Parâmetros da descoberta sob demanda. Os limites existem para nunca disparar uma varredura
 * descontrolada: timeout curto por host, teto de concorrência, teto de hosts e orçamento total.
 */
data class DiscoveryRequest(
    val ports: List<Int> = PrintServiceProtocol.SCAN_PORTS,
    val probeTimeoutMillis: Int = DEFAULT_PROBE_TIMEOUT_MILLIS,
    val totalDurationMillis: Long = DEFAULT_TOTAL_DURATION_MILLIS,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val maxHosts: Int = DEFAULT_MAX_HOSTS,
    val identifyTimeoutMillis: Int = DEFAULT_IDENTIFY_TIMEOUT_MILLIS,
) {
    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 600
        const val DEFAULT_TOTAL_DURATION_MILLIS = 20_000L
        const val DEFAULT_MAX_CONCURRENCY = 48
        const val DEFAULT_MAX_HOSTS = 254
        const val DEFAULT_IDENTIFY_TIMEOUT_MILLIS = 3_000
    }
}

/**
 * Descoberta sob demanda de impressoras na rede local. Só existe enquanto o Flow é coletado.
 * Cada emissão é o estado ATUAL de um host (o mesmo IP pode ser emitido várias vezes, conforme
 * mDNS, varredura e identificação completam). Nunca envia job de impressão.
 */
interface PrinterDiscovery {
    fun discover(request: DiscoveryRequest): Flow<DiscoveredPrinter>
}

/** Um serviço DNS-SD resolvido, como o Android NSD entregou. */
data class MdnsServiceHit(
    val serviceType: String,
    val serviceName: String,
    val hostAddress: String,
    val hostName: String?,
    val port: Int,
    val txt: Map<String, String>,
)

/** Fonte mDNS/NSD (Android NSD na implementação real, fake nos testes). */
interface MdnsPrinterDiscovery {
    fun discover(serviceType: String): Flow<MdnsServiceHit>
}

data class OpenPort(val ipAddress: String, val port: Int)

/** Varredura TCP da subnet local nas portas pedidas. Emite só host:porta que aceitou conexão. */
interface SubnetScanner {
    fun scan(request: DiscoveryRequest): Flow<OpenPort>
}

/** Probe de alcançabilidade TCP de um host:porta — só abre/fecha socket, nunca escreve bytes. */
interface HostReachabilityProbe {
    suspend fun probe(ipAddress: String, port: Int, timeoutMillis: Int): Boolean
}

/** Nome do host por DNS reverso; nulo quando não há nome (nunca devolve o próprio IP). */
interface HostnameResolver {
    suspend fun resolve(ipAddress: String, timeoutMillis: Int): String?
}

data class LocalIpv4(val address: String, val prefixLength: Int)

/** Endereço IPv4 do próprio Android na rede atual (para calcular a subnet a varrer). */
interface LocalNetworkInfoProvider {
    fun current(): LocalIpv4?
}
