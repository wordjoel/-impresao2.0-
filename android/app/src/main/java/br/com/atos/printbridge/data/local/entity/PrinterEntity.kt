package br.com.atos.printbridge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey val id: String,
    val storeId: String?,
    val name: String,
    val model: String,
    val ipAddress: String,
    val port: Int,
    val protocol: String,
    val language: String,
    val isDefault: Boolean,
    val enabled: Boolean,
    val lastSeenAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    // v7 — perfil de mídia para a rasterização de documentos (Fase 1). O defaultValue espelha o
    // DEFAULT da migration 6→7, para o schema do Room bater com o banco migrado.
    @ColumnInfo(defaultValue = "203") val dpi: Int = 203,
    @ColumnInfo(defaultValue = "100") val labelWidthMm: Int = 100,
    @ColumnInfo(defaultValue = "72") val labelHeightMm: Int = 72,
    // v8 — identidade e capacidades descobertas (IPP/SNMP/mDNS). Nulo = não informado pela impressora.
    val manufacturer: String? = null,
    val modelName: String? = null,
    val hostname: String? = null,
    val ippPath: String? = null,
    val capabilities: String? = null,
)
