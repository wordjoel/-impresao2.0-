package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidMediaProfileException
import br.com.atos.printbridge.domain.error.InvalidPortException
import br.com.atos.printbridge.domain.error.InvalidPrinterAddressException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.testutil.FakePrinterRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrinterUseCasesTest {

    private val repository = FakePrinterRepository()
    private val saveUseCase = SavePrinterUseCase(repository)
    private val getUseCase = GetDefaultPrinterUseCase(repository)
    private val markSeen = MarkPrinterSeenUseCase(repository)
    private val observeAll = ObservePrintersUseCase(repository)
    private val observeByStore = ObservePrintersByStoreUseCase(repository)

    private suspend fun seedPrinter(
        id: String? = null,
        storeId: String? = "store-1",
        isDefault: Boolean = false,
        enabled: Boolean = true,
    ): Printer = saveUseCase(
        id = id,
        storeId = storeId,
        name = "Expedição 01",
        model = PrinterModel.TSC_MB241,
        ipAddress = "192.168.0.50",
        port = 9100,
        language = PrinterLanguage.ZPL,
        isDefault = isDefault,
        enabled = enabled,
    )

    @Test
    fun `saving a printer persists storeId and default fields`() = runTest {
        val printer = seedPrinter(storeId = "store-1")

        assertEquals("store-1", printer.storeId)
        assertNull(printer.lastSeenAt)
        assertEquals(PrinterModel.TSC_MB241, printer.model)
        assertEquals(true, printer.enabled)
        assertNotNull(printer.id)
    }

    @Test
    fun `saving twice preserves createdAt and storeId`() = runTest {
        val first = seedPrinter(storeId = "store-1")

        val second = saveUseCase(
            id = first.id,
            storeId = "store-2",
            name = "Expedição 01",
            model = PrinterModel.TSC_MB241,
            ipAddress = "192.168.0.50",
            port = 9100,
            language = PrinterLanguage.ZPL,
            isDefault = false,
            enabled = true,
        )

        assertEquals(first.createdAt, second.createdAt)
        assertEquals("store-2", second.storeId)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `null storeId on update preserves previously bound store`() = runTest {
        val first = seedPrinter(storeId = "store-1")

        val updated = saveUseCase(
            id = first.id,
            storeId = null,
            name = "Expedição 01",
            model = PrinterModel.TSC_MB241,
            ipAddress = "192.168.0.50",
            port = 9100,
            language = PrinterLanguage.ZPL,
            isDefault = false,
            enabled = true,
        )

        assertEquals("store-1", updated.storeId)
    }

    @Test
    fun `observeByStore partitions printers by store`() = runTest {
        seedPrinter(id = "p1", storeId = "store-1")
        seedPrinter(id = "p2", storeId = "store-2")
        seedPrinter(id = "p3", storeId = null)

        assertEquals(3, observeAll().first().size)
        assertEquals(listOf("p1"), observeByStore("store-1").first().map { it.id })
    }

    @Test
    fun `invalid address or port is rejected`() = runTest {
        val badAddress = runCatching {
            saveUseCase(
                id = null,
                storeId = null,
                name = "Bad",
                model = PrinterModel.TSC_MB241,
                ipAddress = "not an ip",
                port = 9100,
                language = PrinterLanguage.ZPL,
                isDefault = false,
                enabled = true,
            )
        }.exceptionOrNull()
        val badPort = runCatching {
            saveUseCase(
                id = null,
                storeId = null,
                name = "Bad",
                model = PrinterModel.TSC_MB241,
                ipAddress = "192.168.0.50",
                port = -1,
                language = PrinterLanguage.ZPL,
                isDefault = false,
                enabled = true,
            )
        }.exceptionOrNull()

        assertEquals(InvalidPrinterAddressException::class.java, badAddress?.javaClass)
        assertEquals(InvalidPortException::class.java, badPort?.javaClass)
    }

    @Test
    fun `markSeen throws when printer does not exist`() = runTest {
        val result = runCatching { markSeen("does-not-exist") }.exceptionOrNull()

        assertEquals(PrinterNotFoundException::class.java, result?.javaClass)
    }

    @Test
    fun `markSeen on existing printer only updates that printer`() = runTest {
        seedPrinter(id = "p1")
        seedPrinter(id = "p2")

        markSeen("p1")

        val p1 = repository.getById("p1")
        val p2 = repository.getById("p2")
        assertNotNull(p1?.lastSeenAt)
        assertNull(p2?.lastSeenAt)
    }

    @Test
    fun `default printer is unique`() = runTest {
        val p1 = seedPrinter(id = "p1", isDefault = true)
        seedPrinter(id = "p2", isDefault = false)
        seedPrinter(id = "p3", isDefault = false)

        assertEquals(p1.id, getUseCase()?.id)
        assertEquals(1, observeAll().first().count { it.isDefault })
    }

    // ---------- Perfil de mídia (Fase 1, Etapa 1) ----------

    @Test
    fun `new printer gets the 203 dpi 100x72 reference media profile`() = runTest {
        val printer = seedPrinter()

        assertEquals(Triple(203, 100, 72), Triple(printer.dpi, printer.labelWidthMm, printer.labelHeightMm))
    }

    @Test
    fun `media profile is saved and kept on later edits that do not touch it`() = runTest {
        val first = saveUseCase(
            id = null, storeId = "store-1", name = "Balança", model = PrinterModel.TSC_ML241,
            ipAddress = "10.0.0.9", port = 9100, language = PrinterLanguage.ZPL, isDefault = false,
            enabled = true, dpi = 300, labelWidthMm = 60, labelHeightMm = 40,
        )

        val renamed = saveUseCase(
            id = first.id, storeId = null, name = "Balança 2", model = PrinterModel.TSC_ML241,
            ipAddress = "10.0.0.9", port = 9100, language = PrinterLanguage.ZPL, isDefault = false, enabled = true,
        )

        assertEquals(Triple(300, 60, 40), Triple(renamed.dpi, renamed.labelWidthMm, renamed.labelHeightMm))
    }

    @Test
    fun `unsupported dpi or out of range paper is rejected`() = runTest {
        assertThrows(InvalidMediaProfileException::class.java) {
            kotlinx.coroutines.runBlocking {
                saveUseCase(null, null, "x", PrinterModel.TSC_ML241, "10.0.0.9", 9100, PrinterLanguage.ZPL, false, true, dpi = 250)
            }
        }
        assertThrows(InvalidMediaProfileException::class.java) {
            kotlinx.coroutines.runBlocking {
                saveUseCase(null, null, "x", PrinterModel.TSC_ML241, "10.0.0.9", 9100, PrinterLanguage.ZPL, false, true, labelWidthMm = 200)
            }
        }
    }

    @Test
    fun `reactivating a printer wakes the queue, deactivating does not`() = runTest {
        val printer = seedPrinter(enabled = false)
        val dispatcher = br.com.atos.printbridge.testutil.FakeQueueDispatcher()
        val setEnabled = SetPrinterEnabledUseCase(repository, dispatcher)

        setEnabled(printer.id, false)
        assertEquals(0, dispatcher.requestCount)

        setEnabled(printer.id, true)
        assertEquals(true, repository.getById(printer.id)?.enabled)
        assertEquals(1, dispatcher.requestCount)
    }
}
