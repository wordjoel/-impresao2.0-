package br.com.atos.printbridge.data.mapper

import br.com.atos.printbridge.data.local.entity.PrinterEntity
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterCapabilities
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol

fun PrinterEntity.toDomain(): Printer = Printer(
    id = id,
    storeId = storeId,
    name = name,
    model = runCatching { PrinterModel.valueOf(model) }.getOrDefault(PrinterModel.GENERIC_ZPL),
    ipAddress = ipAddress,
    port = port,
    protocol = runCatching { PrinterProtocol.valueOf(protocol) }.getOrDefault(PrinterProtocol.TCP),
    language = runCatching { PrinterLanguage.valueOf(language) }.getOrDefault(PrinterLanguage.ZPL),
    isDefault = isDefault,
    enabled = enabled,
    lastSeenAt = lastSeenAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dpi = dpi,
    labelWidthMm = labelWidthMm,
    labelHeightMm = labelHeightMm,
    manufacturer = manufacturer,
    modelName = modelName,
    hostname = hostname,
    ippPath = ippPath,
    capabilities = PrinterCapabilities.decode(capabilities),
)

fun Printer.toEntity(): PrinterEntity = PrinterEntity(
    id = id,
    storeId = storeId,
    name = name,
    model = model.name,
    ipAddress = ipAddress,
    port = port,
    protocol = protocol.name,
    language = language.name,
    isDefault = isDefault,
    enabled = enabled,
    lastSeenAt = lastSeenAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dpi = dpi,
    labelWidthMm = labelWidthMm,
    labelHeightMm = labelHeightMm,
    manufacturer = manufacturer,
    modelName = modelName,
    hostname = hostname,
    ippPath = ippPath,
    capabilities = capabilities?.encode(),
)
