package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.Device
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    fun observeAll(): Flow<List<Device>>
    fun observeByStore(storeId: String): Flow<List<Device>>
    suspend fun getById(deviceId: String): Device?
    suspend fun upsert(device: Device)
    suspend fun delete(deviceId: String)
    suspend fun markSeen(deviceId: String, lastSeenAt: Long)
}