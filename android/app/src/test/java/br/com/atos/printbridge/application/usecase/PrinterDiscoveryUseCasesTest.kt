package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveredService
import br.com.atos.printbridge.domain.discovery.DiscoverySource
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.testutil.FakeIppGateway
import br.com.atos.printbridge.testutil.FakePrinterDiscovery
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterDiscoveryUseCasesTest {

    @Test
    fun `discover probes the registered print ports plus the configured extra raw port`() = runTest {
        val discovery = FakePrinterDiscovery()
        DiscoverPrintersUseCase(discovery, FakeSettingsRepository(AppSettings(discoveryPort = 9120)))()
        assertEquals(listOf(631, 9100, 515, 9120), discovery.lastRequest?.ports)

        DiscoverPrintersUseCase(discovery, FakeSettingsRepository(AppSettings(discoveryPort = 9100)))()
        assertEquals("9100 já está na lista; não duplica", listOf(631, 9100, 515), discovery.lastRequest?.ports)
    }

    private fun ippService(path: String = "ipp/print") = DiscoveredService(
        PrintServiceProtocol.IPP, 631, resourcePath = path, sources = setOf(DiscoverySource.SUBNET_SCAN), verified = true,
    )

    @Test
    fun `verified ipp printer is saved with the identity and capabilities it declared`() = runTest {
        val repository = FakePrinterRepository()
        val caps = FakeIppGateway.description().capabilities
        val saved = SaveDiscoveredPrinterUseCase(SavePrinterUseCase(repository))(
            DiscoveredPrinter(
                ipAddress = "192.168.15.60",
                hostname = "sec30cda71c5205.lan",
                name = "Samsung M2070 Series (SEC30CDA71C5205)",
                manufacturer = "Samsung",
                model = "M2070 Series",
                identitySource = "IPP",
                services = listOf(ippService()),
                capabilities = caps,
            ),
        )

        assertEquals(PrinterProtocol.IPP, saved.protocol)
        assertEquals(631, saved.port)
        assertEquals("ipp/print", saved.ippPath)
        assertEquals("Samsung", saved.manufacturer)
        assertEquals("M2070 Series", saved.modelName)
        assertEquals("sec30cda71c5205.lan", saved.hostname)
        assertEquals(caps, repository.getById(saved.id)?.capabilities)
        assertEquals(PrinterModel.UNKNOWN, saved.model)
        assertTrue(saved.enabled)
    }

    @Test
    fun `printer that did not say its name is saved by ip and never as a tsc model`() = runTest {
        val saved = SaveDiscoveredPrinterUseCase(SavePrinterUseCase(FakePrinterRepository()))(
            DiscoveredPrinter(
                ipAddress = "192.168.1.20",
                services = listOf(DiscoveredService(PrintServiceProtocol.RAW, 9100, sources = setOf(DiscoverySource.MDNS), verified = true)),
            ),
        )

        assertEquals("Impressora 192.168.1.20", saved.name)
        assertEquals(PrinterModel.UNKNOWN, saved.model)
        assertNull(saved.displayManufacturer)
        assertNull(saved.displayModel)
        assertEquals(PrinterProtocol.TCP, saved.protocol)
        assertEquals("linguagem RAW não se descobre sem imprimir", PrinterLanguage.AUTO, saved.language)
    }

    @Test
    fun `host without a confirmed print service is refused with the real reason`() = runTest {
        val error = runCatching {
            SaveDiscoveredPrinterUseCase(SavePrinterUseCase(FakePrinterRepository()))(
                DiscoveredPrinter(
                    ipAddress = "192.168.1.30",
                    services = listOf(DiscoveredService(PrintServiceProtocol.RAW, 9100, sources = setOf(DiscoverySource.SUBNET_SCAN))),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is PrinterCapabilityException)
        assertTrue(error!!.message!!.contains("linguagem da impressora não foi identificada"))
    }
}
