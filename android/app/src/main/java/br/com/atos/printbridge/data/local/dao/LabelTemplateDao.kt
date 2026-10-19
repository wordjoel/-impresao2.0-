package br.com.atos.printbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import br.com.atos.printbridge.data.local.entity.LabelTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelTemplateDao {
    @Query("SELECT * FROM label_templates ORDER BY name ASC")
    fun observeAll(): Flow<List<LabelTemplateEntity>>

    @Query("SELECT COUNT(*) FROM label_templates")
    suspend fun count(): Int

    @Query("SELECT * FROM label_templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LabelTemplateEntity?

    @Query("SELECT * FROM label_templates WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): LabelTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: LabelTemplateEntity)

    @Query("DELETE FROM label_templates WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE label_templates SET isDefault = 0 WHERE id != :id")
    suspend fun clearDefaultExcept(id: String)

    @Query("UPDATE label_templates SET isDefault = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDefault(id: String, now: Long)
}
