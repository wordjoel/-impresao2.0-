package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidDeviceException
import br.com.atos.printbridge.domain.model.DeviceStatus
import br.com.atos.printbridge.testutil.FakeDeviceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceUseCasesTest {

    private val repository = FakeDeviceRepository()
    private val save = SaveDeviceUseCase(repository)
    private val observeAll = ObserveDevicesUseCase(repository)
    private val observeByStore = ObserveDevicesByStoreUseCase(repository)
    private val getById = GetDeviceUseCase(repository)
    private val delete = DeleteDeviceUseCase(repository)
    private val markSeen = MarkDeviceSeenUseCase(repository)

    private suspend fun seedDevice(deviceId: String = "dev-1", storeId: String? = "store-1") =
        save(
            deviceId = deviceId,
            storeId = storeId,
            deviceName = "Caixa 3",
            manufacturer = "Sunmi",
            model = "V2 Pro",
            osVersion = "9",
            appVersion = "1.0.0",
        )

    @Test
    fun `saving a device persists defaults and metadata`() = runTest {
        val device = seedDevice()

        assertEquals(DeviceStatus.REGISTERED, device.status)
        assertEquals("Sunmi", device.manufacturer)
        assertEquals(device, getById("dev-1"))
    }

    @Test
    fun `blank deviceId or name is rejected`() = runTest {
        val blankId = runCatching {
            save(
                deviceId = "  ",
                storeId = null,
                deviceName = "Caixa",
                manufacturer = "Sunmi",
                model = "V2 Pro",
                osVersion = "9",
                appVersion = "1.0.0",
            )
        }.exceptionOrNull()
        val blankName = runCatching {
            save(
                deviceId = "dev-1",
                storeId = null,
                deviceName = " ",
                manufacturer = "Sunmi",
                model = "V2 Pro",
                osVersion = "9",
                appVersion = "1.0.0",
            )
        }.exceptionOrNull()

        assertEquals(InvalidDeviceException::class.java, blankId?.javaClass)
        assertEquals(InvalidDeviceException::class.java, blankName?.javaClass)
    }

    @Test
    fun `observeByStore partitions devices by store and observeAll lists all`() = runTest {
        seedDevice(deviceId = "dev-1", storeId = "store-1")
        seedDevice(deviceId = "dev-2", storeId = "store-2")

        assertEquals(2, observeAll().first().size)
        assertEquals(listOf("dev-1"), observeByStore("store-1").first().map { it.deviceId })
    }

    @Test
    fun `markSeen updates lastSeenAt without activating`() = runTest {
        seedDevice(deviceId = "dev-1")
        seedDevice(deviceId = "dev-2")

        markSeen("dev-1")

        assertEquals(DeviceStatus.REGISTERED, getById("dev-1")?.status)
        assertEquals(true, getById("dev-1")?.lastSeenAt != null)
        assertNull(getById("dev-2")?.lastSeenAt)
    }

    @Test
    fun `delete removes only the targeted device`() = runTest {
        seedDevice(deviceId = "dev-1")
        seedDevice(deviceId = "dev-2")

        delete("dev-1")

        assertNull(getById("dev-1"))
        assertEquals("dev-2", getById("dev-2")?.deviceId)
    }
}