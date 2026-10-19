package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.DeviceDao
import br.com.atos.printbridge.data.mapper.toDomain
import br.com.atos.printbridge.data.mapper.toEntity
import br.com.atos.printbridge.domain.model.Device
import br.com.atos.printbridge.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DeviceRepositoryImpl @Inject constructor(
    private val dao: DeviceDao,
) : DeviceRepository {

    override fun observeAll(): Flow<List<Device>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByStore(storeId: String): Flow<List<Device>> =
        dao.observeByStore(storeId).map { list -> list.map { it.toDomain() } }

    override suspend fun getById(deviceId: String): Device? = dao.getById(deviceId)?.toDomain()

    override suspend fun upsert(device: Device) = dao.upsert(device.toEntity())

    override suspend fun delete(deviceId: String) = dao.delete(deviceId)

    override suspend fun markSeen(deviceId: String, lastSeenAt: Long) =
        dao.markSeen(deviceId, lastSeenAt)
}