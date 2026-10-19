package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveredService
import br.com.atos.printbridge.domain.discovery.DiscoverySource
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.printing.discovery.NsdServiceMapper
import br.com.atos.printbridge.testutil.FakeIppGateway
import br.com.atos.printbridge.testutil.FakePrinterRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate de preservação: descoberta real é ADITIVA. Nada que já estava cadastrado (TSC, TSPL, ZPL,
 * porta, DPI, papel, padrão) muda ou some quando uma impressora nova é encontrada e salva.
 */
class PreservationGateTest {

    private fun tsc(id: String, model: PrinterModel, language: PrinterLanguage, port: Int, isDefault: Boolean = false) = Printer(
        id = id, storeId = "loja-1", name = "TSC $id", model = model, ipAddress = "10.1.2.${id.last().code % 50 + 10}", port = port,
        protocol = PrinterProtocol.TCP, language = language, isDefault = isDefault, enabled = true,
        lastSeenAt = null, createdAt = 1, updatedAt = 1, dpi = 300, labelWidthMm = 100, labelHeightMm = 150,
    )

    @Test
    fun `saving a discovered office printer keeps every existing TSC printer untouched`() = runTest {
        val repo = FakePrinterRepository()
        val before = listOf(
            tsc("a", PrinterModel.TSC_ML241, PrinterLanguage.ZPL, 9100, isDefault = true),
            tsc("b", PrinterModel.TSC_MB241, PrinterLanguage.TSPL, 9100),
            tsc("c", PrinterModel.OTHER_TSC, PrinterLanguage.TSPL, 6101),
        )
        before.forEach { repo.upsert(it) }

        SaveDiscoveredPrinterUseCase(SavePrinterUseCase(repo))(
            DiscoveredPrinter(
                ipAddress = "192.168.15.60", manufacturer = "HP", model = "LaserJet Pro M203dw", identitySource = "IPP",
                services = listOf(DiscoveredService(PrintServiceProtocol.IPP, 631, "ipp/print", setOf(DiscoverySource.MDNS), verified = true)),
                capabilities = FakeIppGateway.description().capabilities,
            ),
        )

        val after = repo.observeAll().first()
        assertEquals(4, after.size)
        before.forEach { original -> assertEquals("TSC ${original.id} mudou", original, after.single { it.id == original.id }) }
        assertEquals("a padrão continua a mesma", "a", repo.getDefault()?.id)
        assertEquals(PrinterProtocol.IPP, after.single { it.ipAddress == "192.168.15.60" }.protocol)
    }

    @Test
    fun `tsc family announced by the printer is saved as tsc with raw and editable language`() = runTest {
        val repo = FakePrinterRepository()
        val saved = SaveDiscoveredPrinterUseCase(SavePrinterUseCase(repo))(
            DiscoveredPrinter(
                ipAddress = "10.1.2.70", name = "TSC ML241P", identitySource = "mDNS",
                services = listOf(DiscoveredService(PrintServiceProtocol.RAW, 9100, sources = setOf(DiscoverySource.MDNS), verified = true)),
            ),
        )
        assertEquals(PrinterModel.TSC_ML241, saved.model)
        assertEquals("TSC", saved.displayManufacturer)
        assertEquals(PrinterProtocol.TCP, saved.protocol)
        assertEquals(9100, saved.port)
        assertEquals(PrinterModel.TSC_MB241, NsdServiceMapper.modelHint("TSC MB241"))
        assertEquals(PrinterModel.OTHER_TSC, NsdServiceMapper.modelHint("TSC TTP-244 Pro"))
        assertEquals(null, NsdServiceMapper.modelHint("Samsung M2070 Series"))
    }

    @Test
    fun `every TSC model language and manual option is still available`() {
        assertTrue(PrinterModel.entries.containsAll(listOf(PrinterModel.TSC_ML241, PrinterModel.TSC_MB241, PrinterModel.OTHER_TSC, PrinterModel.GENERIC_ZPL)))
        assertTrue(PrinterLanguage.entries.containsAll(listOf(PrinterLanguage.ZPL, PrinterLanguage.TSPL, PrinterLanguage.AUTO)))
        assertEquals(listOf(203, 300), Printer.SUPPORTED_DPI)
    }

    @Test
    fun `editing a tsc printer keeps its port dpi paper language and model`() = runTest {
        val repo = FakePrinterRepository()
        val original = tsc("x", PrinterModel.TSC_MB241, PrinterLanguage.TSPL, 9101)
        repo.upsert(original)

        val edited = SavePrinterUseCase(repo)(
            id = "x", storeId = null, name = "Etiquetas Doca", model = original.model, ipAddress = original.ipAddress,
            port = original.port, language = original.language, isDefault = false, enabled = true,
        )

        assertEquals(original.copy(name = "Etiquetas Doca", updatedAt = edited.updatedAt), edited)
    }
}
