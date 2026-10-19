package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidDeviceException
import br.com.atos.printbridge.domain.model.Device
import br.com.atos.printbridge.domain.model.DeviceStatus
import br.com.atos.printbridge.domain.repository.DeviceRepository
import javax.inject.Inject

class SaveDeviceUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    /**
     * Persiste o registro local do device. `deviceId` é obrigatório e NÃO é gerado aqui — vem do
     * `DeviceIdentityProvider` (derivado do Keystore), nunca deve ser escolhido/forjado pelo
     * chamador. Metadados (manufacturer/model/osVersion/appVersion) são informativos, obtidos do
     * sistema na primeira execução; `status` inicial será REGISTERED até a ativação (Fase 17+).
     */
    suspend operator fun invoke(
        deviceId: String,
        storeId: String?,
        deviceName: String,
        manufacturer: String,
        model: String,
        osVersion: String,
        appVersion: String,
        status: DeviceStatus = DeviceStatus.REGISTERED,
        lastSeenAt: Long? = null,
        activatedAt: Long? = null,
    ): Device {
        if (deviceId.isBlank()) {
            throw InvalidDeviceException("Identificador do dispositivo não pode ser vazio")
        }
        val name = deviceName.trim()
        if (name.isBlank()) {
            throw InvalidDeviceException("Nome do dispositivo não pode ser vazio")
        }
        val device = Device(
            deviceId = deviceId,
            storeId = storeId,
            deviceName = name,
            manufacturer = manufacturer.trim(),
            model = model.trim(),
            osVersion = osVersion.trim(),
            appVersion = appVersion.trim(),
            status = status,
            lastSeenAt = lastSeenAt,
            activatedAt = activatedAt,
        )
        repository.upsert(device)
        return device
    }
}

class DeleteDeviceUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(deviceId: String) = repository.delete(deviceId)
}

class MarkDeviceSeenUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(deviceId: String) {
        repository.markSeen(deviceId, System.currentTimeMillis())
    }
}

class ObserveDevicesUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    operator fun invoke() = repository.observeAll()
}

class ObserveDevicesByStoreUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    operator fun invoke(storeId: String) = repository.observeByStore(storeId)
}

class GetDeviceUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(deviceId: String): Device? = repository.getById(deviceId)
}