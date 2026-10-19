package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.PrinterDao
import br.com.atos.printbridge.data.mapper.toDomain
import br.com.atos.printbridge.data.mapper.toEntity
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.repository.PrinterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PrinterRepositoryImpl @Inject constructor(
    private val dao: PrinterDao,
) : PrinterRepository {

    override fun observeAll(): Flow<List<Printer>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByStore(storeId: String): Flow<List<Printer>> =
        dao.observeByStore(storeId).map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): Printer? = dao.getById(id)?.toDomain()

    override suspend fun getDefault(): Printer? = dao.getDefault()?.toDomain()

    override suspend fun upsert(printer: Printer) {
        dao.upsert(printer.toEntity())
        if (printer.isDefault) {
            dao.clearDefaultExcept(printer.id)
        }
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun setDefault(id: String) {
        val now = System.currentTimeMillis()
        dao.clearDefaultExcept(id)
        dao.markDefault(id, now)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    override suspend fun markSeen(id: String) {
        val now = System.currentTimeMillis()
        dao.markSeen(id, now, now)
    }
}
