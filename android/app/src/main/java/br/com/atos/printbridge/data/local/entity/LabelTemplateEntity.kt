package br.com.atos.printbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Modelo de etiqueta persistido. O layout (lista de elementos em mm) é serializado numa coluna
 * `TEXT` por `LabelLayoutCodec` — o app não tem biblioteca de JSON e esta rodada não adiciona
 * dependências (ver PESOHUB-LABEL-MODULE-AUDIT.md §4/§5).
 */
@Entity(tableName = "label_templates")
data class LabelTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val widthMm: Int,
    val heightMm: Int,
    val dpi: Int,
    val layout: String,
    val isBuiltIn: Boolean,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
