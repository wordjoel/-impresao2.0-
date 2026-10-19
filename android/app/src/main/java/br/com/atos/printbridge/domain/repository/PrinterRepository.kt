package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.Printer
import kotlinx.coroutines.flow.Flow

interface PrinterRepository {
    fun observeAll(): Flow<List<Printer>>
    fun observeByStore(storeId: String): Flow<List<Printer>>
    suspend fun getById(id: String): Printer?
    suspend fun getDefault(): Printer?
    suspend fun upsert(printer: Printer)
    suspend fun delete(id: String)
    suspend fun setDefault(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun markSeen(id: String)
}
