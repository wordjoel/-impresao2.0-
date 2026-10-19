package br.com.atos.printbridge.data.local.db

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.atos.printbridge.data.local.entity.DeviceEntity
import br.com.atos.printbridge.data.local.entity.PrintJobEntity
import br.com.atos.printbridge.data.local.entity.PrinterEntity
import br.com.atos.printbridge.data.local.entity.StoreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testa o Room de verdade (banco em memória via Robolectric) — sem isso, os fakes usados nos
 * testes de use case não garantem que as queries reais (@Query escritas à mão em PrinterDao/
 * PrintJobDao) estão corretas. Roda em JVM (sem emulador) via Robolectric, único jeito de validar
 * SQLite real nesta máquina sem hardware Android disponível.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun printer(id: String = "printer-1", isDefault: Boolean = false, storeId: String? = null) = PrinterEntity(
        id = id,
        storeId = storeId,
        name = "Expedição 01",
        model = "TSC_MB241",
        ipAddress = "192.168.0.50",
        port = 9100,
        protocol = "TCP",
        language = "ZPL",
        isDefault = isDefault,
        enabled = true,
        lastSeenAt = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun job(id: String, printerId: String, status: String, createdAt: Long = 0) = PrintJobEntity(
        id = id,
        printerId = printerId,
        type = "ZPL",
        payload = "^XA^XZ",
        documentName = "etiqueta",
        copies = 1,
        status = status,
        attempts = 0,
        maxAttempts = 3,
        errorMessage = null,
        externalDocumentId = null,
        originalJobId = null,
        createdAt = createdAt,
        startedAt = null,
        sentAt = null,
        updatedAt = createdAt,
        validationWarnings = null,
    )

    @Test
    fun `only one printer can be default at a time`() = runTest {
        db.printerDao().upsert(printer("p1", isDefault = true))
        db.printerDao().upsert(printer("p2", isDefault = false))

        db.printerDao().clearDefaultExcept("p2")
        db.printerDao().markDefault("p2", now = 1)

        assertEquals("p2", db.printerDao().getDefault()?.id)
    }

    @Test
    fun `nextPendingFor returns oldest pending job for that printer only`() = runTest {
        db.printerDao().upsert(printer("p1"))
        db.printJobDao().insert(job("job-newer", "p1", "PENDING", createdAt = 200))
        db.printJobDao().insert(job("job-older", "p1", "PENDING", createdAt = 100))
        db.printJobDao().insert(job("job-other-printer", "p2", "PENDING", createdAt = 50))
        db.printJobDao().insert(job("job-sent", "p1", "SENT", createdAt = 10))

        val next = db.printJobDao().nextPendingFor("p1")

        assertEquals("job-older", next?.id)
    }

    @Test
    fun `active and history queries partition by status correctly`() = runTest {
        db.printerDao().upsert(printer("p1"))
        db.printJobDao().insert(job("pending-job", "p1", "PENDING"))
        db.printJobDao().insert(job("processing-job", "p1", "PROCESSING"))
        db.printJobDao().insert(job("sent-job", "p1", "SENT"))
        db.printJobDao().insert(job("failed-job", "p1", "FAILED"))

        val active = db.printJobDao().observeActive().first()
        val history = db.printJobDao().observeHistory().first()

        assertEquals(setOf("pending-job", "processing-job"), active.map { it.id }.toSet())
        assertEquals(setOf("sent-job", "failed-job"), history.map { it.id }.toSet())
    }

    @Test
    fun `cancel only affects PENDING or FAILED jobs`() = runTest {
        db.printerDao().upsert(printer("p1"))
        db.printJobDao().insert(job("pending-job", "p1", "PENDING"))
        db.printJobDao().insert(job("processing-job", "p1", "PROCESSING"))

        db.printJobDao().cancel("pending-job", now = 1)
        db.printJobDao().cancel("processing-job", now = 1)

        assertEquals("CANCELLED", db.printJobDao().getById("pending-job")?.status)
        assertEquals("PROCESSING", db.printJobDao().getById("processing-job")?.status)
    }

    @Test
    fun `deleting a printer does not require deleting its jobs`() = runTest {
        db.printerDao().upsert(printer("p1"))
        db.printJobDao().insert(job("job-1", "p1", "SENT"))

        db.printerDao().delete("p1")

        assertNull(db.printerDao().getById("p1"))
        assertNotNull(db.printJobDao().getById("job-1"))
    }

    @Test
    fun `observeByStore partitions printers by storeId`() = runTest {
        db.printerDao().upsert(printer("p1", storeId = "store-1"))
        db.printerDao().upsert(printer("p2", storeId = "store-2"))
        db.printerDao().upsert(printer("p3", storeId = null))

        val byStore1 = db.printerDao().observeByStore("store-1").first()
        val all = db.printerDao().observeAll().first()

        assertEquals(listOf("p1"), byStore1.map { it.id })
        assertEquals(setOf("p1", "p2", "p3"), all.map { it.id }.toSet())
    }

    @Test
    fun `markSeen updates lastSeenAt for the targeted printer only`() = runTest {
        db.printerDao().upsert(printer("p1"))
        db.printerDao().upsert(printer("p2"))

        db.printerDao().markSeen("p1", lastSeenAt = 500L, now = 600L)

        assertEquals(500L, db.printerDao().getById("p1")?.lastSeenAt)
        assertEquals(600L, db.printerDao().getById("p1")?.updatedAt)
        assertNull(db.printerDao().getById("p2")?.lastSeenAt)
    }

    private fun store(
        storeId: String = "store-1",
        storeCode: String = "4521",
        storeName: String = "Loja Centro",
        status: String = "UNKNOWN",
        activationStatus: String = "PENDING",
    ) = StoreEntity(
        storeId = storeId,
        storeCode = storeCode,
        storeName = storeName,
        status = status,
        activationStatus = activationStatus,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `store is upserted and replaced by storeId`() = runTest {
        db.storeDao().upsert(store(storeId = "store-1", storeCode = "4521"))
        db.storeDao().upsert(store(storeId = "store-1", storeCode = "9999", storeName = "Renomeada"))

        val persisted = db.storeDao().getById("store-1")

        assertEquals("9999", persisted?.storeCode)
        assertEquals("Renomeada", persisted?.storeName)
    }

    @Test
    fun `store query by storeCode returns matching store only`() = runTest {
        db.storeDao().upsert(store(storeId = "store-1", storeCode = "4521"))
        db.storeDao().upsert(store(storeId = "store-2", storeCode = "7777"))

        assertNotNull(db.storeDao().getByCode("4521"))
        assertNull(db.storeDao().getByCode("0000"))
    }

    @Test
    fun `observeAll orders stores by storeCode`() = runTest {
        db.storeDao().upsert(store(storeId = "store-1", storeCode = "Z9"))
        db.storeDao().upsert(store(storeId = "store-2", storeCode = "A1"))

        val all = db.storeDao().observeAll().first()

        assertEquals(listOf("A1", "Z9"), all.map { it.storeCode })
    }

    @Test
    fun `store delete removes only the targeted store`() = runTest {
        db.storeDao().upsert(store(storeId = "store-1", storeCode = "4521"))
        db.storeDao().upsert(store(storeId = "store-2", storeCode = "7777"))

        db.storeDao().delete("store-1")

        assertNull(db.storeDao().getById("store-1"))
        assertNotNull(db.storeDao().getById("store-2"))
    }

    private fun device(
        deviceId: String = "device-1",
        storeId: String? = "store-1",
        deviceName: String = "Caixa 3",
        status: String = "REGISTERED",
        lastSeenAt: Long? = null,
    ) = DeviceEntity(
        deviceId = deviceId,
        storeId = storeId,
        deviceName = deviceName,
        manufacturer = "Sunmi",
        model = "V2 Pro",
        osVersion = "9",
        appVersion = "1.0.0",
        status = status,
        lastSeenAt = lastSeenAt,
        activatedAt = null,
    )

    @Test
    fun `device is upserted and replaced by deviceId`() = runTest {
        db.deviceDao().upsert(device(deviceId = "device-1", deviceName = "Caixa 3"))
        db.deviceDao().upsert(device(deviceId = "device-1", deviceName = "Caixa 3 renomeada"))

        assertEquals("Caixa 3 renomeada", db.deviceDao().getById("device-1")?.deviceName)
    }

    @Test
    fun `observeAll and observeByStore partition devices correctly`() = runTest {
        db.deviceDao().upsert(device(deviceId = "device-1", storeId = "store-1"))
        db.deviceDao().upsert(device(deviceId = "device-2", storeId = "store-2"))

        val all = db.deviceDao().observeAll().first()
        val byStore1 = db.deviceDao().observeByStore("store-1").first()

        assertEquals(2, all.size)
        assertEquals(listOf("device-1"), byStore1.map { it.deviceId })
    }

    @Test
    fun `markSeen updates lastSeenAt for the targeted device only`() = runTest {
        db.deviceDao().upsert(device(deviceId = "device-1"))
        db.deviceDao().upsert(device(deviceId = "device-2"))

        db.deviceDao().markSeen("device-1", lastSeenAt = 500)

        assertEquals(500L, db.deviceDao().getById("device-1")?.lastSeenAt)
        assertNull(db.deviceDao().getById("device-2")?.lastSeenAt)
    }

    @Test
    fun `device delete removes only the targeted device`() = runTest {
        db.deviceDao().upsert(device(deviceId = "device-1"))
        db.deviceDao().upsert(device(deviceId = "device-2"))

        db.deviceDao().delete("device-1")

        assertNull(db.deviceDao().getById("device-1"))
        assertNotNull(db.deviceDao().getById("device-2"))
    }
}
