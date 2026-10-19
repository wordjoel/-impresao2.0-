package br.com.atos.printbridge.domain.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4SubnetTest {

    @Test
    fun `full 24-bit subnet excludes network and broadcast addresses`() {
        val hosts = Ipv4Subnet.hostsToScan("192.168.1.50", 24, 254)

        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue("192.168.1.0" !in hosts)
        assertTrue("192.168.1.255" !in hosts)
    }

    @Test
    fun `window centered on device when subnet is bigger than cap`() {
        val hosts = Ipv4Subnet.hostsToScan("192.168.1.50", 24, 10)

        assertEquals(listOf("192.168.1.45", "192.168.1.46", "192.168.1.47", "192.168.1.48", "192.168.1.49"), hosts.take(5))
        assertEquals(10, hosts.size)
        assertEquals("192.168.1.54", hosts.last())
    }

    @Test
    fun `window clamped to subnet bounds at the edges`() {
        val hosts = Ipv4Subnet.hostsToScan("192.168.1.2", 24, 10)

        assertEquals("192.168.1.1", hosts.first())
        assertEquals(10, hosts.size)
    }

    @Test
    fun `small 30-bit subnet returns only usable hosts`() {
        val hosts = Ipv4Subnet.hostsToScan("192.168.1.1", 30, 254)

        assertEquals(listOf("192.168.1.1", "192.168.1.2"), hosts)
    }

    @Test
    fun `prefix 32 has no usable hosts`() {
        assertTrue(Ipv4Subnet.hostsToScan("192.168.1.1", 32, 254).isEmpty())
    }

    @Test
    fun `invalid address or zero cap returns empty`() {
        assertTrue(Ipv4Subnet.hostsToScan("not-an-ip", 24, 254).isEmpty())
        assertTrue(Ipv4Subnet.hostsToScan("192.168.1.1", 24, 0).isEmpty())
    }

    @Test
    fun `round trip long to address`() {
        val value = Ipv4Subnet.toLong("10.20.30.40")
        assertEquals("10.20.30.40", Ipv4Subnet.toAddress(value!!))
    }
}