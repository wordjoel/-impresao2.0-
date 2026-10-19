package br.com.atos.printbridge.domain.discovery

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisteredPrinterMatchTest {

    private fun printer(id: String, ip: String, port: Int = 9100, name: String = id, isDefault: Boolean = false) = Printer(
        id = id, storeId = null, name = name, model = PrinterModel.TSC_MB241, ipAddress = ip, port = port,
        protocol = PrinterProtocol.TCP, language = PrinterLanguage.TSPL, isDefault = isDefault, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )

    private fun host(ip: String, vararg ports: Int) = DiscoveredPrinter(
        ipAddress = ip,
        services = ports.map { DiscoveredService(PrintServiceProtocol.RAW, it, sources = setOf(DiscoverySource.SUBNET_SCAN)) },
    )

    @Test
    fun `cadastradas aparecem mesmo sem nada encontrado na rede`() {
        val listing = RegisteredPrinterMatch.list(listOf(printer("a", "127.0.0.1", 9105), printer("b", "10.10.40.32")), emptyList())

        assertEquals(listOf("a", "b"), listing.registered.map { it.printer.id })
        assertTrue(listing.registered.none { it.responding })
        assertTrue(listing.newOnNetwork.isEmpty())
    }

    @Test
    fun `host ja cadastrado nao aparece como novo e marca a cadastrada como respondendo`() {
        val listing = RegisteredPrinterMatch.list(
            listOf(printer("tsc", "10.10.30.74")),
            listOf(host("10.10.30.74", 9100), host("10.10.30.80", 9100)),
        )

        assertTrue(listing.registered.single().responding)
        assertEquals(listOf("10.10.30.80"), listing.newOnNetwork.map { it.ipAddress })
    }

    @Test
    fun `host achado mas sem a porta cadastrada nao conta como respondendo`() {
        val status = RegisteredPrinterMatch.list(listOf(printer("tsc", "10.10.30.74", 9100)), listOf(host("10.10.30.74", 631))).registered.single()

        assertFalse(status.responding)
        assertEquals("10.10.30.74", status.found?.ipAddress)
    }

    @Test
    fun `cadastrada que nao respondeu continua na lista sem host associado`() {
        val status = RegisteredPrinterMatch.list(listOf(printer("off", "10.10.30.50")), listOf(host("10.10.30.74", 9100))).registered.single()

        assertEquals("off", status.printer.id)
        assertNull(status.found)
        assertFalse(status.responding)
    }

    @Test
    fun `padrao primeiro e depois por nome`() {
        val listing = RegisteredPrinterMatch.list(
            listOf(printer("1", "10.0.0.1", name = "zeta"), printer("2", "10.0.0.2", name = "Alfa"), printer("3", "10.0.0.3", name = "meio", isDefault = true)),
            emptyList(),
        )

        assertEquals(listOf("3", "2", "1"), listing.registered.map { it.printer.id })
    }
}
