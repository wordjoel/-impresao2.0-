package br.com.atos.printbridge.data.mapper

import br.com.atos.printbridge.data.local.entity.DeviceEntity
import br.com.atos.printbridge.domain.model.Device
import br.com.atos.printbridge.domain.model.DeviceStatus

fun DeviceEntity.toDomain(): Device = Device(
    deviceId = deviceId,
    storeId = storeId,
    deviceName = deviceName,
    manufacturer = manufacturer,
    model = model,
    osVersion = osVersion,
    appVersion = appVersion,
    status = runCatching { DeviceStatus.valueOf(status) }.getOrDefault(DeviceStatus.REGISTERED),
    lastSeenAt = lastSeenAt,
    activatedAt = activatedAt,
)

fun Device.toEntity(): DeviceEntity = DeviceEntity(
    deviceId = deviceId,
    storeId = storeId,
    deviceName = deviceName,
    manufacturer = manufacturer,
    model = model,
    osVersion = osVersion,
    appVersion = appVersion,
    status = status.name,
    lastSeenAt = lastSeenAt,
    activatedAt = activatedAt,
)