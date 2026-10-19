package br.com.atos.printbridge.data.mapper

import br.com.atos.printbridge.data.local.entity.StoreEntity
import br.com.atos.printbridge.domain.model.Store
import br.com.atos.printbridge.domain.model.StoreActivationStatus
import br.com.atos.printbridge.domain.model.StoreStatus

fun StoreEntity.toDomain(): Store = Store(
    storeId = storeId,
    storeCode = storeCode,
    storeName = storeName,
    status = runCatching { StoreStatus.valueOf(status) }.getOrDefault(StoreStatus.UNKNOWN),
    activationStatus = runCatching { StoreActivationStatus.valueOf(activationStatus) }
        .getOrDefault(StoreActivationStatus.PENDING),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Store.toEntity(): StoreEntity = StoreEntity(
    storeId = storeId,
    storeCode = storeCode,
    storeName = storeName,
    status = status.name,
    activationStatus = activationStatus.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)