package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.label.LabelTemplate
import kotlinx.coroutines.flow.Flow

interface LabelTemplateRepository {
    fun observeAll(): Flow<List<LabelTemplate>>
    suspend fun count(): Int
    suspend fun getById(id: String): LabelTemplate?
    suspend fun getDefault(): LabelTemplate?
    suspend fun upsert(template: LabelTemplate)
    suspend fun delete(id: String)
    suspend fun setDefault(id: String)
}
