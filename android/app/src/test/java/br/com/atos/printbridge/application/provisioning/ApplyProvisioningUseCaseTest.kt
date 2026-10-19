package br.com.atos.printbridge.application.provisioning

import br.com.atos.printbridge.application.usecase.SavePrinterUseCase
import br.com.atos.printbridge.application.usecase.SaveStoreUseCase
import br.com.atos.printbridge.application.usecase.SetAdminPinUseCase
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.provisioning.ProvisionedPrinter
import br.com.atos.printbridge.domain.provisioning.ProvisioningConfig
import br.com.atos.printbridge.domain.security.PinHasher
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.FakeStoreRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyProvisioningUseCaseTest {

    private val stores = FakeStoreRepository()
    private val printers = FakePrinterRepository()
    private val settings = FakeSettingsRepository(AppSettings())
    private val apply = ApplyProvisioningUseCase(
        stores, SaveStoreUseCase(stores), printers, SavePrinterUseCase(printers), settings, SetAdminPinUseCase(settings),
    )

    private val expedicao = ProvisionedPrinter("Expedição", "10.1.2.50", dpi = 203, paperSize = "100x72", isDefault = true)
    private val balanca = ProvisionedPrinter("Balança", "10.1.2.51", language = PrinterLanguage.TSPL, dpi = 300, paperSize = "60x40")
    private val loja = ProvisioningConfig(storeCode = "4521", storeName = "Loja Centro", printers = listOf(expedicao, balanca))

    @Test
    fun `fresh collector gets store and printers configured`() = runTest {
        val result = apply(loja, "mdm")

        assertTrue(result.summary, result.applied)
        assertEquals(2, result.printersCreated)
        val store = stores.getByCode("4521")!!
        assertEquals("Loja Centro", store.storeName)
        val all = printers.observeAll().first()
        assertEquals(setOf("10.1.2.50", "10.1.2.51"), all.map { it.ipAddress }.toSet())
        assertTrue(all.all { it.storeId == store.storeId })
        val tspl = all.single { it.ipAddress == "10.1.2.51" }
        assertEquals(Triple(PrinterLanguage.TSPL, 300, 60), Triple(tspl.language, tspl.dpi, tspl.labelWidthMm))
        assertEquals("10.1.2.50", printers.getDefault()?.ipAddress)
    }

    @Test
    fun `reapplying the same config never duplicates store or printers`() = runTest {
        apply(loja, "mdm")
        val second = apply(loja.copy(storeName = "Loja Centro (reformada)"), "mdm")

        assertEquals(0, second.printersCreated)
        assertEquals(2, second.printersUpdated)
        assertEquals(1, stores.observeAll().first().size)
        assertEquals(2, printers.observeAll().first().size)
        assertEquals("Loja Centro (reformada)", stores.getByCode("4521")!!.storeName)
    }

    @Test
    fun `printer matched by ip and port keeps its id when renamed`() = runTest {
        apply(loja, "mdm")
        val idBefore = printers.observeAll().first().single { it.ipAddress == "10.1.2.50" }.id

        apply(loja.copy(printers = listOf(expedicao.copy(name = "Expedição Doca 2"), balanca)), "mdm")

        val after = printers.observeAll().first().single { it.ipAddress == "10.1.2.50" }
        assertEquals(idBefore, after.id)
        assertEquals("Expedição Doca 2", after.name)
    }

    @Test
    fun `replace mode removes store printers that left the MDM list`() = runTest {
        apply(loja, "mdm")

        val result = apply(loja.copy(printers = listOf(expedicao), replacePrinters = true), "mdm")

        assertEquals(1, result.printersRemoved)
        assertEquals(listOf("10.1.2.50"), printers.observeAll().first().map { it.ipAddress })
    }

    @Test
    fun `single printer becomes the default even if not marked`() = runTest {
        apply(ProvisioningConfig("4521", "Loja", listOf(balanca)), "arquivo")
        assertEquals("10.1.2.51", printers.getDefault()?.ipAddress)
    }

    @Test
    fun `any invalid item rejects the whole config and nothing is written`() = runTest {
        val bad = loja.copy(printers = listOf(expedicao, balanca.copy(ipAddress = "10.1.2.999"), balanca.copy(ipAddress = "10.1.2.52", dpi = 250)))

        val result = apply(bad, "mdm")

        assertFalse(result.applied)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors[0].contains("IP inválido"))
        assertTrue(result.errors[1].contains("DPI 250"))
        assertEquals(0, printers.observeAll().first().size)
        assertEquals(0, stores.observeAll().first().size)
    }

    @Test
    fun `duplicated printers and two defaults are rejected`() = runTest {
        val result = apply(loja.copy(printers = listOf(expedicao, expedicao.copy(name = "X"), balanca.copy(isDefault = true))), "mdm")

        assertFalse(result.applied)
        assertTrue(result.errors.any { it.contains("repetidas") })
        assertTrue(result.errors.any { it.contains("mais de uma") })
    }

    @Test
    fun `operational settings and admin pin are applied only when informed`() = runTest {
        apply(ProvisioningConfig(null, null, maxAttempts = 7, historyRetentionDays = 60, adminPin = "4321"), "mdm")

        val s = settings.current()
        assertEquals(7, s.maxAttempts)
        assertEquals(60, s.historyRetentionDays)
        assertEquals(AppSettings().retryBaseIntervalMillis, s.retryBaseIntervalMillis)
        assertTrue(PinHasher.verify("4321", s.adminPinHash!!))
    }

    @Test
    fun `fingerprint changes with content and ignores nothing relevant`() {
        assertEquals(loja.fingerprint(), loja.copy().fingerprint())
        assertTrue(loja.fingerprint() != loja.copy(printers = listOf(expedicao.copy(port = 9101), balanca)).fingerprint())
        assertTrue(loja.fingerprint() != loja.copy(adminPin = "1234").fingerprint())
    }
}
