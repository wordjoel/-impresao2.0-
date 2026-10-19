package br.com.atos.printbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import br.com.atos.printbridge.data.local.entity.StoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {
    @Query("SELECT * FROM stores ORDER BY storeCode ASC")
    fun observeAll(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores WHERE storeId = :storeId LIMIT 1")
    suspend fun getById(storeId: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE storeCode = :storeCode LIMIT 1")
    suspend fun getByCode(storeCode: String): StoreEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(store: StoreEntity)

    @Query("DELETE FROM stores WHERE storeId = :storeId")
    suspend fun delete(storeId: String)
}