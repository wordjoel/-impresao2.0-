package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.Store
import kotlinx.coroutines.flow.Flow

interface StoreRepository {
    fun observeAll(): Flow<List<Store>>
    suspend fun getById(storeId: String): Store?
    suspend fun getByCode(storeCode: String): Store?
    suspend fun upsert(store: Store)
    suspend fun delete(storeId: String)
}