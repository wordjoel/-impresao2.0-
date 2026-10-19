package br.com.atos.printbridge.testutil

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.HostReachabilityProbe
import br.com.atos.printbridge.domain.discovery.HostnameResolver
import br.com.atos.printbridge.domain.discovery.LocalIpv4
import br.com.atos.printbridge.domain.discovery.LocalNetworkInfoProvider
import br.com.atos.printbridge.domain.discovery.MdnsPrinterDiscovery
import br.com.atos.printbridge.domain.discovery.MdnsServiceHit
import br.com.atos.printbridge.domain.discovery.OpenPort
import br.com.atos.printbridge.domain.discovery.PrinterDiscovery
import br.com.atos.printbridge.domain.discovery.SubnetScanner
import br.com.atos.printbridge.domain.ipp.IppEndpoint
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppGateway
import br.com.atos.printbridge.domain.ipp.IppJobAccepted
import br.com.atos.printbridge.domain.ipp.IppPrinterDescription
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.model.PrinterCapabilities
import br.com.atos.printbridge.domain.snmp.SnmpIdentity
import br.com.atos.printbridge.domain.snmp.SnmpIdentityProbe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class FakePrinterDiscovery(private val emitted: MutableSharedFlow<DiscoveredPrinter> = MutableSharedFlow()) :
    PrinterDiscovery {
    var lastRequest: DiscoveryRequest? = null
        private set

    override fun discover(request: DiscoveryRequest): Flow<DiscoveredPrinter> {
        lastRequest = request
        return emitted
    }

    suspend fun emit(printer: DiscoveredPrinter) = emitted.emit(printer)
}

/**
 * mDNS fake: para cada tipo pedido, emite os hits daquele tipo e (opcionalmente) fica pendurado
 * esperando cancelamento, como o NSD real. O parsing do TXT é coberto por NsdServiceMapperTest.
 */
class FakeMdnsPrinterDiscovery(
    private val results: List<MdnsServiceHit> = emptyList(),
    private val hangAfterEmitting: Boolean = true,
) : MdnsPrinterDiscovery {
    private val cancelled = AtomicInteger(0)
    val wasCancelled: Boolean get() = cancelled.get() > 0
    val requestedTypes = mutableListOf<String>()

    override fun discover(serviceType: String): Flow<MdnsServiceHit> {
        synchronized(requestedTypes) { requestedTypes += serviceType }
        return flow {
            results.filter { it.serviceType == serviceType }.forEach { emit(it) }
            if (hangAfterEmitting) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }
    }
}

/** Varredura fake: emite [results] e (opcionalmente) fica pendurada aguardando cancelamento. */
class FakeSubnetScanner(
    private val results: List<OpenPort> = emptyList(),
    private val hangAfterEmitting: Boolean = true,
) : SubnetScanner {
    var wasCancelled = false
        private set
    var lastRequest: DiscoveryRequest? = null
        private set

    override fun scan(request: DiscoveryRequest): Flow<OpenPort> {
        lastRequest = request
        return flow {
            results.forEach { emit(it) }
            if (hangAfterEmitting) {
                try {
                    awaitCancellation()
                } finally {
                    wasCancelled = true
                }
            }
        }
    }
}

/** IPP fake: responde Get-Printer-Attributes só nos endpoints cadastrados; o resto falha com [defaultFailure]. */
class FakeIppGateway : IppGateway {
    val printers = mutableMapOf<String, IppPrinterDescription>()
    var defaultFailure = IppResult.Failure(IppFailureKind.NOT_IPP, "HTTP 404")
    val attributeCalls = mutableListOf<String>()
    val printJobs = mutableListOf<Triple<IppEndpoint, String, ByteArray>>()
    var printResult: IppResult<IppJobAccepted> = IppResult.Ok(IppJobAccepted(jobId = 42, statusCode = 0))

    override suspend fun getPrinterAttributes(endpoint: IppEndpoint, timeoutMillis: Int): IppResult<IppPrinterDescription> {
        synchronized(attributeCalls) { attributeCalls += endpoint.uri }
        return printers[endpoint.uri]?.let { IppResult.Ok(it) } ?: defaultFailure
    }

    override suspend fun printJob(
        endpoint: IppEndpoint,
        documentFormat: String,
        jobName: String,
        copies: Int,
        document: ByteArray,
        timeoutMillis: Int,
    ): IppResult<IppJobAccepted> {
        printJobs += Triple(endpoint, documentFormat, document)
        return printResult
    }

    companion object {
        fun description(
            manufacturer: String? = "Samsung",
            model: String? = "M2070 Series",
            formats: List<String> = listOf("image/pwg-raster", "image/urf"),
        ) = IppPrinterDescription(
            manufacturer = manufacturer,
            model = model,
            makeAndModel = listOfNotNull(manufacturer, model).joinToString(" "),
            name = "SEC30CDA71C5205",
            info = null,
            location = null,
            state = 3,
            stateReasons = listOf("none"),
            acceptingJobs = true,
            capabilities = PrinterCapabilities(
                documentFormats = formats,
                resolutionsDpi = listOf(600),
                defaultResolutionDpi = 600,
                media = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
                defaultMedia = "iso_a4_210x297mm",
                sides = listOf("one-sided"),
                colorModes = listOf("monochrome"),
                pwgRasterTypes = listOf("black_1", "sgray_8"),
                pwgRasterResolutionsDpi = listOf(300, 600),
            ),
        )
    }
}

class FakeSnmpIdentityProbe(private val answers: Map<String, SnmpIdentity> = emptyMap()) : SnmpIdentityProbe {
    override suspend fun identify(ipAddress: String, timeoutMillis: Int): SnmpIdentity? = answers[ipAddress]
}

class FakeHostnameResolver(private val names: Map<String, String> = emptyMap()) : HostnameResolver {
    override suspend fun resolve(ipAddress: String, timeoutMillis: Int): String? = names[ipAddress]
}

/**
 * Probe fake com controle de alcance (por IP ou por "ip:porta"), atraso e contagem de
 * concorrência — testa corte de timeout, limite de concorrência e cancelamento sem rede real.
 */
class FakeHostReachabilityProbe : HostReachabilityProbe {
    val reachable = mutableSetOf<String>()
    var delayMillis: Long = 0
    val totalCalls = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    private val concurrent = AtomicInteger(0)

    override suspend fun probe(ipAddress: String, port: Int, timeoutMillis: Int): Boolean {
        totalCalls.incrementAndGet()
        val now = concurrent.incrementAndGet()
        runCatching { maxConcurrent.accumulateAndGet(now, ::max) }
        return try {
            if (delayMillis > 0) delay(delayMillis)
            ipAddress in reachable || "$ipAddress:$port" in reachable
        } finally {
            concurrent.decrementAndGet()
        }
    }
}

class FakeLocalNetworkInfoProvider(private var local: LocalIpv4? = null) : LocalNetworkInfoProvider {
    override fun current(): LocalIpv4? = local
    fun set(value: LocalIpv4?) { local = value }
}
