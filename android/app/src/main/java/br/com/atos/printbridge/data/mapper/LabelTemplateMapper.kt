package br.com.atos.printbridge.data.mapper

import br.com.atos.printbridge.data.local.entity.LabelTemplateEntity
import br.com.atos.printbridge.domain.model.label.LabelLayoutCodec
import br.com.atos.printbridge.domain.model.label.LabelTemplate

object LabelTemplateMapper {

    fun toDomain(entity: LabelTemplateEntity): LabelTemplate = LabelTemplate(
        id = entity.id,
        name = entity.name,
        widthMm = entity.widthMm,
        heightMm = entity.heightMm,
        dpi = entity.dpi,
        elements = LabelLayoutCodec.decode(entity.layout),
        isBuiltIn = entity.isBuiltIn,
        isDefault = entity.isDefault,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    fun toEntity(template: LabelTemplate): LabelTemplateEntity = LabelTemplateEntity(
        id = template.id,
        name = template.name,
        widthMm = template.widthMm,
        heightMm = template.heightMm,
        dpi = template.dpi,
        layout = LabelLayoutCodec.encode(template.elements),
        isBuiltIn = template.isBuiltIn,
        isDefault = template.isDefault,
        createdAt = template.createdAt,
        updatedAt = template.updatedAt,
    )
}
