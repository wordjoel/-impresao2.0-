package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.StoreDao
import br.com.atos.printbridge.data.mapper.toDomain
import br.com.atos.printbridge.data.mapper.toEntity
import br.com.atos.printbridge.domain.model.Store
import br.com.atos.printbridge.domain.repository.StoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class StoreRepositoryImpl @Inject constructor(
    private val dao: StoreDao,
) : StoreRepository {

    override fun observeAll(): Flow<List<Store>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(storeId: String): Store? = dao.getById(storeId)?.toDomain()

    override suspend fun getByCode(storeCode: String): Store? = dao.getByCode(storeCode)?.toDomain()

    override suspend fun upsert(store: Store) = dao.upsert(store.toEntity())

    override suspend fun delete(storeId: String) = dao.delete(storeId)
}