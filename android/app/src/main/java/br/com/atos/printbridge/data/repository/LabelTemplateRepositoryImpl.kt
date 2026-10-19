package br.com.atos.printbridge.data.repository

import br.com.atos.printbridge.data.local.dao.LabelTemplateDao
import br.com.atos.printbridge.data.mapper.LabelTemplateMapper
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.repository.LabelTemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabelTemplateRepositoryImpl @Inject constructor(
    private val dao: LabelTemplateDao,
) : LabelTemplateRepository {

    override fun observeAll(): Flow<List<LabelTemplate>> =
        dao.observeAll().map { list -> list.map(LabelTemplateMapper::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getById(id: String): LabelTemplate? =
        dao.getById(id)?.let(LabelTemplateMapper::toDomain)

    override suspend fun getDefault(): LabelTemplate? =
        dao.getDefault()?.let(LabelTemplateMapper::toDomain)

    override suspend fun upsert(template: LabelTemplate) {
        dao.upsert(LabelTemplateMapper.toEntity(template))
        // Invariante "no máximo um padrão": mesma regra do PrinterRepositoryImpl — salvar um modelo
        // já marcado como padrão não pode deixar o anterior também marcado (o `getDefault` pegaria
        // um LIMIT 1 arbitrário entre dois).
        if (template.isDefault) dao.clearDefaultExcept(template.id)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun setDefault(id: String) {
        dao.clearDefaultExcept(id)
        dao.markDefault(id, System.currentTimeMillis())
    }
}
