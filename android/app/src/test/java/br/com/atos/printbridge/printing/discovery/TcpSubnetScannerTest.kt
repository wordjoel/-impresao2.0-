package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.OpenPort
import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.LocalIpv4
import br.com.atos.printbridge.testutil.FakeHostReachabilityProbe
import br.com.atos.printbridge.testutil.FakeLocalNetworkInfoProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSubnetScannerTest {

    @Test
    fun `emits only reachable hosts with the requested port`() = runTest {
        val probe = FakeHostReachabilityProbe().apply { reachable += "192.168.1.10" }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        val found = scanner.scan(DiscoveryRequest(ports = listOf(9100))).toList()

        assertEquals(listOf("192.168.1.10"), found.map { it.ipAddress })
        assertEquals(9100, found.single().port)
    }

    @Test
    fun `probes every requested port and reports only the ones that accepted`() = runTest {
        val probe = FakeHostReachabilityProbe().apply { reachable += "192.168.1.10:631"; reachable += "192.168.1.11:515" }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        val found = scanner.scan(DiscoveryRequest(ports = listOf(631, 9100, 515))).toList()

        assertEquals(setOf(OpenPort("192.168.1.10", 631), OpenPort("192.168.1.11", 515)), found.toSet())
        assertEquals(253 * 3, probe.totalCalls.get())
    }

    @Test
    fun `does not probe the device's own address`() = runTest {
        val probe = FakeHostReachabilityProbe().apply { reachable += "192.168.1.50" }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        val found = scanner.scan(DiscoveryRequest(ports = listOf(9100))).toList()

        assertTrue(found.isEmpty())
        assertEquals(253, probe.totalCalls.get())
    }

    @Test
    fun `caps the number of probed hosts`() = runTest {
        val probe = FakeHostReachabilityProbe()
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        scanner.scan(DiscoveryRequest(ports = listOf(9100), maxHosts = 10, maxConcurrency = 10)).toList()

        assertEquals(9, probe.totalCalls.get())
    }

    @Test
    fun `limits concurrency of probes`() = runTest {
        val probe = FakeHostReachabilityProbe().apply {
            delayMillis = 20
            reachable += "192.168.1.10"
        }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        scanner.scan(DiscoveryRequest(ports = listOf(9100), maxConcurrency = 4, probeTimeoutMillis = 400)).toList()

        assertEquals(4, probe.maxConcurrent.get())
        assertTrue(probe.maxConcurrent.get() <= 4)
    }

    @Test
    fun `host that outlives probe timeout is discarded`() = runTest {
        val probe = FakeHostReachabilityProbe().apply {
            delayMillis = 10_000
            reachable += "192.168.1.99"
        }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        val found = scanner.scan(
            DiscoveryRequest(ports = listOf(9100), maxHosts = 4, maxConcurrency = 4, probeTimeoutMillis = 100),
        ).toList()

        assertTrue(found.isEmpty())
    }

    @Test
    fun `returns empty when there is no local ipv4`() = runTest {
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(null), FakeHostReachabilityProbe())

        assertTrue(scanner.scan(DiscoveryRequest(ports = listOf(9100))).toList().isEmpty())
    }

    @Test
    fun `cancelling the collection stops the scan`() = runTest {
        val probe = FakeHostReachabilityProbe().apply {
            delayMillis = 10
            reachable += "192.168.1.10"
        }
        val scanner = TcpSubnetScanner(FakeLocalNetworkInfoProvider(LocalIpv4("192.168.1.50", 24)), probe)

        val job = launch {
            scanner.scan(DiscoveryRequest(ports = listOf(9100))).toList()
        }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}