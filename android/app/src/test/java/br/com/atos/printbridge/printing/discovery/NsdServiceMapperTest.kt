package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.DiscoverySource
import br.com.atos.printbridge.domain.discovery.MdnsServiceHit
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdServiceMapperTest {

    private fun hit(
        type: String = "_ipp._tcp.",
        address: String = "192.168.15.60",
        port: Int = 631,
        txt: Map<String, String> = emptyMap(),
        name: String = "Samsung M2070 Series (SEC30CDA71C5205)",
        hostName: String? = "SEC30CDA71C5205.local",
    ) = MdnsServiceHit(type, name, address, hostName, port, txt)

    @Test
    fun `ipp service keeps advertised port and resource path but stays unverified until it answers`() {
        val d = NsdServiceMapper.apply(null, hit(txt = mapOf("rp" to "ipp/print", "ty" to "Samsung M2070 Series", "usb_MFG" to "Samsung", "usb_MDL" to "M2070 Series")))!!

        val s = d.services.single()
        assertEquals(PrintServiceProtocol.IPP, s.protocol)
        assertEquals(631, s.port)
        assertEquals("ipp/print", s.resourcePath)
        assertFalse("IPP só é verificado pelo Get-Printer-Attributes", s.verified)
        assertEquals(setOf(DiscoverySource.MDNS), s.sources)
        assertEquals("Samsung", d.manufacturer)
        assertEquals("M2070 Series", d.model)
        assertEquals("mDNS", d.identitySource)
        assertEquals("SEC30CDA71C5205.local", d.hostname)
        assertEquals("Samsung M2070 Series (SEC30CDA71C5205)", d.name)
    }

    @Test
    fun `raw pdl datastream announced by the device counts as a declared print service`() {
        val d = NsdServiceMapper.apply(null, hit(type = "_pdl-datastream._tcp.", port = 9100))!!
        assertTrue(d.services.single().verified)
        assertEquals(PrintServiceProtocol.RAW, d.preferredService?.protocol)
    }

    @Test
    fun `port zero is only a candidate on the registered port never a confirmed one`() {
        val d = NsdServiceMapper.apply(null, hit(type = "_pdl-datastream._tcp.", port = 0))!!
        val s = d.services.single()
        assertEquals(9100, s.port)
        assertFalse(s.verified)
        assertTrue(s.note!!.contains("não informada"))
    }

    @Test
    fun `ipps and lpd are listed as not supported`() {
        val ipps = NsdServiceMapper.apply(null, hit(type = "_ipps._tcp."))!!
        val both = NsdServiceMapper.apply(ipps, hit(type = "_printer._tcp.", port = 515))!!
        assertEquals(2, both.services.size)
        assertNull(both.preferredService)
        assertTrue(both.problem!!.contains("protocolo não suportado"))
    }

    @Test
    fun `non ipv4 address and unknown service type are ignored`() {
        assertNull(NsdServiceMapper.apply(null, hit(address = "fe80::1")))
        assertNull(NsdServiceMapper.apply(null, hit(type = "_http._tcp.")))
    }

    @Test
    fun `no txt identity means no manufacturer or model and no identity source`() {
        val d = NsdServiceMapper.apply(null, hit(txt = emptyMap(), hostName = "192.168.15.60"))!!
        assertNull(d.manufacturer)
        assertNull(d.model)
        assertNull(d.identitySource)
        assertNull("hostname igual ao IP não é hostname", d.hostname)
    }
}
