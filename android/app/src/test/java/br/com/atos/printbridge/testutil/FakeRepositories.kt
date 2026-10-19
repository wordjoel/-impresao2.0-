package br.com.atos.printbridge.testutil

import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.Device
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.Store
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.queue.QueueDispatcher
import br.com.atos.printbridge.domain.repository.DeviceRepository
import br.com.atos.printbridge.domain.repository.LabelTemplateRepository
import br.com.atos.printbridge.domain.repository.LicenseRepository
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.repository.StoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class FakePrinterRepository : PrinterRepository {
    private val state = MutableStateFlow<List<Printer>>(emptyList())

    override fun observeAll(): Flow<List<Printer>> = state
    override fun observeByStore(storeId: String): Flow<List<Printer>> =
        state.map { list -> list.filter { it.storeId == storeId } }

    override suspend fun getById(id: String): Printer? = state.value.firstOrNull { it.id == id }
    override suspend fun getDefault(): Printer? = state.value.firstOrNull { it.isDefault }

    override suspend fun upsert(printer: Printer) {
        val withoutExisting = state.value.filterNot { it.id == printer.id }
        state.value = if (printer.isDefault) {
            (withoutExisting.map { it.copy(isDefault = false) } + printer)
        } else {
            withoutExisting + printer
        }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun setDefault(id: String) {
        state.value = state.value.map { it.copy(isDefault = it.id == id) }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun markSeen(id: String) {
        state.value = state.value.map { if (it.id == id) it.copy(lastSeenAt = System.currentTimeMillis()) else it }
    }

    fun seed(printer: Printer) {
        state.value = state.value + printer
    }
}

class FakePrintJobRepository : PrintJobRepository {
    private val state = MutableStateFlow<List<PrintJob>>(emptyList())
    val snapshot: List<PrintJob> get() = state.value

    override fun observeActive(): Flow<List<PrintJob>> = state.map { jobs -> jobs.filter { it.status.isActive } }

    override fun observeHistory(): Flow<List<PrintJob>> = state.map { jobs -> jobs.filterNot { it.status.isActive } }

    override suspend fun getById(id: String): PrintJob? = state.value.firstOrNull { it.id == id }

    override suspend fun insert(job: PrintJob) {
        state.value = state.value + job
    }

    override suspend fun update(job: PrintJob) {
        state.value = state.value.map { if (it.id == job.id) job else it }
    }

    override suspend fun nextPendingFor(printerId: String): PrintJob? =
        state.value.filter { it.printerId == printerId && it.status == PrintJobStatus.PENDING }
            .minWithOrNull(compareBy<PrintJob> { it.createdAt }.thenBy { it.pageIndex ?: 0 })

    override suspend fun insertAll(jobs: List<PrintJob>) {
        state.value = state.value + jobs
    }

    override suspend fun getByGroupId(groupId: String): List<PrintJob> =
        state.value.filter { it.groupId == groupId }.sortedBy { it.pageIndex }

    override suspend fun countActiveAhead(job: PrintJob): Int =
        state.value.count {
            it.printerId == job.printerId && it.status.isActive &&
                (it.createdAt < job.createdAt || (it.createdAt == job.createdAt && (it.pageIndex ?: 0) < (job.pageIndex ?: 0)))
        }

    override suspend fun findOrphanedProcessing(): List<PrintJob> =
        state.value.filter { it.status == PrintJobStatus.PROCESSING }

    override suspend fun cancel(id: String) {
        state.value = state.value.map {
            if (it.id == id && (it.status == PrintJobStatus.PENDING || it.status == PrintJobStatus.FAILED)) {
                it.copy(status = PrintJobStatus.CANCELLED)
            } else it
        }
    }

    override suspend fun deleteByOperator(id: String): Boolean {
        var removed = false
        state.value = state.value.map {
            if (it.id == id && (it.status == PrintJobStatus.PENDING || it.status == PrintJobStatus.FAILED)) {
                removed = true
                it.copy(status = PrintJobStatus.DELETED_BY_OPERATOR)
            } else it
        }
        return removed
    }

    override suspend fun purgeHistoryOlderThan(timestampMillis: Long) {
        state.value = state.value.filter { it.status.isActive || it.updatedAt >= timestampMillis }
    }

    override suspend fun printerIdsWithPendingJobs(): List<String> =
        state.value.filter { it.status == PrintJobStatus.PENDING }.map { it.printerId }.distinct()
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): StateFlow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

class FakeLicenseRepository(initial: LicenseState = LicenseState()) : LicenseRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): StateFlow<LicenseState> = state
    override suspend fun current(): LicenseState = state.value
    override suspend fun update(transform: (LicenseState) -> LicenseState) {
        state.value = transform(state.value)
    }
}

class FakeNetworkStateProvider(private var available: Boolean = true) : NetworkStateProvider {
    override fun isNetworkAvailable(): Boolean = available
    fun setAvailable(value: Boolean) { available = value }
}

/**
 * Fake de DeviceIdentityProvider para testar ORQUESTRAÇÃO (RespondToDeviceChallengeUseCase),
 * nunca para testar a garantia criptográfica em si — essa só existe de verdade contra o Android
 * Keystore real (não testável em JVM pura, ver AndroidKeystoreDeviceIdentityProvider).
 */
class FakeDeviceIdentityProvider(private val id: String = "fake-device-id") : DeviceIdentityProvider {
    var lastSignedChallenge: ByteArray? = null
        private set

    override fun deviceId(): String = id
    override fun publicKey(): ByteArray = "fake-public-key".toByteArray()

    override fun sign(challenge: ByteArray): ByteArray {
        lastSignedChallenge = challenge
        // Assinatura fake determinística (challenge invertido) — só para provar que o use case
        // passa os bytes certos adiante e devolve o resultado certo, nunca usada para verificar
        // uma propriedade criptográfica real.
        return challenge.reversedArray()
    }
}

class FakeQueueDispatcher : QueueDispatcher {
    var requestCount = 0
        private set
    override fun requestProcessing() { requestCount++ }
}

class FakeStoreRepository : StoreRepository {
    private val state = MutableStateFlow<List<Store>>(emptyList())

    override fun observeAll(): Flow<List<Store>> = state
    override suspend fun getById(storeId: String): Store? = state.value.firstOrNull { it.storeId == storeId }
    override suspend fun getByCode(storeCode: String): Store? = state.value.firstOrNull { it.storeCode == storeCode }

    override suspend fun upsert(store: Store) {
        state.value = state.value.filterNot { it.storeId == store.storeId } + store
    }

    override suspend fun delete(storeId: String) {
        state.value = state.value.filterNot { it.storeId == storeId }
    }

    fun seed(store: Store) {
        state.value = state.value + store
    }
}

class FakeLabelTemplateRepository : LabelTemplateRepository {
    private val state = MutableStateFlow<List<LabelTemplate>>(emptyList())
    val snapshot: List<LabelTemplate> get() = state.value

    override fun observeAll(): Flow<List<LabelTemplate>> = state.map { list -> list.sortedBy { it.name } }

    override suspend fun count(): Int = state.value.size
    override suspend fun getById(id: String): LabelTemplate? = state.value.firstOrNull { it.id == id }
    override suspend fun getDefault(): LabelTemplate? = state.value.firstOrNull { it.isDefault }

    override suspend fun upsert(template: LabelTemplate) {
        val withoutExisting = state.value.filterNot { it.id == template.id }
        state.value = if (template.isDefault) {
            // Mesma invariante do LabelTemplateRepositoryImpl/PrinterRepositoryImpl: no máximo um padrão.
            withoutExisting.map { it.copy(isDefault = false) } + template
        } else {
            withoutExisting + template
        }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun setDefault(id: String) {
        state.value = state.value.map { it.copy(isDefault = it.id == id) }
    }
}

class FakeDeviceRepository : DeviceRepository {
    private val state = MutableStateFlow<List<Device>>(emptyList())

    override fun observeAll(): Flow<List<Device>> = state
    override fun observeByStore(storeId: String): Flow<List<Device>> =
        state.map { list -> list.filter { it.storeId == storeId } }

    override suspend fun getById(deviceId: String): Device? = state.value.firstOrNull { it.deviceId == deviceId }

    override suspend fun upsert(device: Device) {
        state.value = state.value.filterNot { it.deviceId == device.deviceId } + device
    }

    override suspend fun delete(deviceId: String) {
        state.value = state.value.filterNot { it.deviceId == deviceId }
    }

    override suspend fun markSeen(deviceId: String, lastSeenAt: Long) {
        state.value = state.value.map { if (it.deviceId == deviceId) it.copy(lastSeenAt = lastSeenAt) else it }
    }

    fun seed(device: Device) {
        state.value = state.value + device
    }
}
