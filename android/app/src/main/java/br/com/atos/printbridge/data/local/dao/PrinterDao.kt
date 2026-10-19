package br.com.atos.printbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import br.com.atos.printbridge.data.local.entity.PrinterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers ORDER BY name ASC")
    fun observeAll(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE storeId = :storeId ORDER BY name ASC")
    fun observeByStore(storeId: String): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PrinterEntity?

    @Query("SELECT * FROM printers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(printer: PrinterEntity)

    @Query("DELETE FROM printers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE printers SET isDefault = 0 WHERE id != :id")
    suspend fun clearDefaultExcept(id: String)

    @Query("UPDATE printers SET isDefault = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDefault(id: String, now: Long)

    @Query("UPDATE printers SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE printers SET lastSeenAt = :lastSeenAt, updatedAt = :now WHERE id = :id")
    suspend fun markSeen(id: String, lastSeenAt: Long, now: Long)
}
