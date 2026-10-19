package br.com.atos.printbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val storeId: String?,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    val status: String,
    val lastSeenAt: Long?,
    val activatedAt: Long?,
)