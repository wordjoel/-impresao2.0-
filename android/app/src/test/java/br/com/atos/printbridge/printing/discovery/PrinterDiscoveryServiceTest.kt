package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.MdnsServiceHit
import br.com.atos.printbridge.domain.discovery.OpenPort
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.snmp.SnmpIdentity
import br.com.atos.printbridge.testutil.FakeHostnameResolver
import br.com.atos.printbridge.testutil.FakeIppGateway
import br.com.atos.printbridge.testutil.FakeMdnsPrinterDiscovery
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakeSnmpIdentityProbe
import br.com.atos.printbridge.testutil.FakeSubnetScanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterDiscoveryServiceTest {

    private val request = DiscoveryRequest(totalDurationMillis = 1_000)

    private fun service(
        mdns: FakeMdnsPrinterDiscovery = FakeMdnsPrinterDiscovery(),
        scanner: FakeSubnetScanner = FakeSubnetScanner(),
        ipp: FakeIppGateway = FakeIppGateway(),
        snmp: FakeSnmpIdentityProbe = FakeSnmpIdentityProbe(),
        hostnames: FakeHostnameResolver = FakeHostnameResolver(),
        network: Boolean = true,
    ) = PrinterDiscoveryServiceImpl(mdns, scanner, FakeNetworkStateProvider(network), ipp, snmp, hostnames)

    /** Estado final de cada host = última emissão daquele IP. */
    private fun List<DiscoveredPrinter>.finalByIp() = associateBy { it.ipAddress }

    @Test
    fun `open 631 is verified by IPP and the printer's own identity and capabilities are used`() = runTest {
        val ipp = FakeIppGateway().apply { printers["ipp://192.168.15.60:631/ipp/print"] = FakeIppGateway.description() }
        val found = service(
            scanner = FakeSubnetScanner(listOf(OpenPort("192.168.15.60", 631))),
            ipp = ipp,
            hostnames = FakeHostnameResolver(mapOf("192.168.15.60" to "impressora-sala.lan")),
        ).discover(request).toList().finalByIp()

        val d = found.getValue("192.168.15.60")
        assertEquals("Samsung", d.manufacturer)
        assertEquals("M2070 Series", d.model)
        assertEquals("IPP", d.identitySource)
        assertEquals("impressora-sala.lan", d.hostname)
        assertEquals("Ociosa", d.state)
        val preferred = d.preferredService!!
        assertEquals(PrintServiceProtocol.IPP, preferred.protocol)
        assertEquals(631, preferred.port)
        assertEquals("ipp/print", preferred.resourcePath)
        assertTrue(preferred.verified)
        assertTrue(d.capabilities!!.supportsPwgRaster)
        assertNull(d.problem)
    }

    @Test
    fun `mdns rp path is tried first and the same host from mdns and scan is merged`() = runTest {
        val ipp = FakeIppGateway().apply { printers["ipp://192.168.15.60:631/printers/lj"] = FakeIppGateway.description() }
        val mdns = FakeMdnsPrinterDiscovery(
            listOf(MdnsServiceHit("_ipp._tcp.", "Samsung M2070", "192.168.15.60", null, 631, mapOf("rp" to "printers/lj"))),
        )
        val found = service(
            mdns = mdns,
            scanner = FakeSubnetScanner(listOf(OpenPort("192.168.15.60", 631), OpenPort("192.168.15.60", 9100))),
            ipp = ipp,
        ).discover(request).toList().finalByIp()

        assertEquals(1, found.size)
        val d = found.getValue("192.168.15.60")
        assertEquals(listOf(631, 9100), d.services.map { it.port }.sorted())
        assertEquals("printers/lj", d.preferredService!!.resourcePath)
        assertEquals("ipp://192.168.15.60:631/printers/lj", ipp.attributeCalls.first())
        assertEquals(PrintServiceProtocol.entries.map { it.mdnsType }.toSet(), mdns.requestedTypes.toSet())
    }

    @Test
    fun `port 631 that does not answer IPP is reported with the technical cause, not hidden`() = runTest {
        val ipp = FakeIppGateway().apply { defaultFailure = IppResult.Failure(IppFailureKind.NOT_IPP, "HTTP 404 em http://192.168.15.1:631/") }
        val found = service(scanner = FakeSubnetScanner(listOf(OpenPort("192.168.15.1", 631))), ipp = ipp)
            .discover(request).toList().finalByIp()

        val d = found.getValue("192.168.15.1")
        assertNull(d.preferredService)
        assertEquals("Impressora encontrada, mas o serviço de impressão não respondeu.", d.problem)
        assertTrue(d.services.single().note!!.contains("HTTP 404"))
        // Todos os caminhos candidatos tentados antes de desistir.
        assertEquals(3, ipp.attributeCalls.size)
    }

    @Test
    fun `raw 9100 alone is not a confirmed printer and snmp only names known printer makers`() = runTest {
        val snmp = FakeSnmpIdentityProbe(
            mapOf(
                "192.168.15.35" to SnmpIdentity("Samsung M2070 Series; V3.00", "SEC30C", "Samsung M2070 Series"),
                "192.168.15.200" to SnmpIdentity("Linux 4.9 armv7l", "nas", null),
            ),
        )
        val found = service(
            scanner = FakeSubnetScanner(listOf(OpenPort("192.168.15.35", 9100), OpenPort("192.168.15.200", 9100))),
            snmp = snmp,
        ).discover(request).toList().finalByIp()

        val printer = found.getValue("192.168.15.35")
        assertEquals("Samsung", printer.manufacturer)
        assertEquals("M2070 Series", printer.model)
        assertEquals("SNMP", printer.identitySource)
        assertNull("porta aberta não é serviço confirmado", printer.preferredService)
        assertEquals(9100, printer.unverifiedRaw!!.port)

        val nas = found.getValue("192.168.15.200")
        assertNull(nas.manufacturer)
        assertNull(nas.identitySource)
    }

    @Test
    fun `lpd only host is listed as unsupported`() = runTest {
        val found = service(scanner = FakeSubnetScanner(listOf(OpenPort("10.0.0.9", 515)))).discover(request).toList().finalByIp()
        assertTrue(found.getValue("10.0.0.9").problem!!.contains("não suportado"))
    }

    @Test
    fun `ends the round after the total budget even if sources stay alive`() = runTest {
        val mdns = FakeMdnsPrinterDiscovery(hangAfterEmitting = true)
        val scanner = FakeSubnetScanner(hangAfterEmitting = true)

        val found = service(mdns = mdns, scanner = scanner).discover(DiscoveryRequest(totalDurationMillis = 5_000)).toList()

        assertTrue(found.isEmpty())
        assertTrue(mdns.wasCancelled)
        assertTrue(scanner.wasCancelled)
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun `completes immediately without emitting when there is no network`() = runTest {
        val scanner = FakeSubnetScanner(listOf(OpenPort("10.0.0.1", 9100)))
        val found = service(scanner = scanner, network = false).discover(request).toList()
        assertTrue(found.isEmpty())
        assertNull(scanner.lastRequest)
    }

    @Test
    fun `cancelling the collector cancels mdns and subnet scan`() = runTest {
        val mdns = FakeMdnsPrinterDiscovery()
        val scanner = FakeSubnetScanner()
        val job = launch { service(mdns = mdns, scanner = scanner).discover(DiscoveryRequest(totalDurationMillis = 60_000)).toList() }
        advanceTimeBy(100)
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(mdns.wasCancelled)
        assertTrue(scanner.wasCancelled)
        assertFalse(job.isActive)
    }
}
