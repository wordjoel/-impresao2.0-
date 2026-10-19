package br.com.atos.printbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey val storeId: String,
    val storeCode: String,
    val storeName: String,
    val status: String,
    val activationStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
)