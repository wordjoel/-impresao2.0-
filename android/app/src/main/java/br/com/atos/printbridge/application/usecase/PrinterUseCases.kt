package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidMediaProfileException
import br.com.atos.printbridge.domain.error.InvalidPortException
import br.com.atos.printbridge.domain.error.InvalidPrinterAddressException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.queue.QueueDispatcher
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.validation.NetworkAddressValidator
import java.util.UUID
import javax.inject.Inject

class SavePrinterUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    suspend operator fun invoke(
        id: String?,
        storeId: String?,
        name: String,
        model: br.com.atos.printbridge.domain.model.PrinterModel,
        ipAddress: String,
        port: Int,
        language: br.com.atos.printbridge.domain.model.PrinterLanguage,
        isDefault: Boolean,
        enabled: Boolean,
        // Perfil de mídia (Fase 1). Nulo = mantém o valor atual da impressora (ou o padrão, se nova).
        dpi: Int? = null,
        labelWidthMm: Int? = null,
        labelHeightMm: Int? = null,
        // v8 — protocolo e identidade. Nulo = mantém o atual (ou RAW/"não informado", se nova).
        protocol: PrinterProtocol? = null,
        identity: DiscoveredIdentity? = null,
    ): Printer {
        val trimmedAddress = ipAddress.trim()
        if (!NetworkAddressValidator.isValidAddress(trimmedAddress)) {
            throw InvalidPrinterAddressException(trimmedAddress)
        }
        if (!NetworkAddressValidator.isValidPort(port)) {
            throw InvalidPortException(port)
        }
        val existing = id?.let { repository.getById(it) }
        val finalProtocol = protocol ?: existing?.protocol ?: PrinterProtocol.TCP
        val finalDpi = dpi ?: existing?.dpi ?: Printer.DEFAULT_DPI
        val finalWidth = labelWidthMm ?: existing?.labelWidthMm ?: Printer.DEFAULT_LABEL_WIDTH_MM
        val finalHeight = labelHeightMm ?: existing?.labelHeightMm ?: Printer.DEFAULT_LABEL_HEIGHT_MM
        // Perfil em mm/dpi é de etiqueta térmica por socket. Impressora IPP usa as capacidades
        // que ela mesma declarou (papel, resolução) — não se valida uma etiqueta que não existe.
        if (finalProtocol == PrinterProtocol.TCP) {
            if (finalDpi !in Printer.SUPPORTED_DPI) {
                throw InvalidMediaProfileException("DPI $finalDpi não suportado (use ${Printer.SUPPORTED_DPI.joinToString(" ou ")})")
            }
            // Faixa física da linha TSC de etiquetas: até 118mm de papel; altura de 5mm a 1m.
            if (finalWidth !in 10..118 || finalHeight !in 5..1000) {
                throw InvalidMediaProfileException("papel ${finalWidth}x${finalHeight} mm fora da faixa (largura 10-118, altura 5-1000)")
            }
        }
        val finalIppPath = identity?.ippPath ?: existing?.ippPath
        if (finalProtocol == PrinterProtocol.IPP && finalIppPath == null) {
            throw br.com.atos.printbridge.domain.error.PrinterCapabilityException("impressora IPP sem caminho de serviço verificado — use a busca na rede para identificá-la")
        }
        val now = System.currentTimeMillis()
        val printer = Printer(
            id = id ?: UUID.randomUUID().toString(),
            storeId = storeId ?: existing?.storeId,
            name = name.trim(),
            model = model,
            ipAddress = trimmedAddress,
            port = port,
            protocol = finalProtocol,
            language = language,
            isDefault = isDefault,
            enabled = enabled,
            lastSeenAt = identity?.let { now } ?: existing?.lastSeenAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            dpi = finalDpi,
            labelWidthMm = finalWidth,
            labelHeightMm = finalHeight,
            manufacturer = identity?.manufacturer ?: existing?.manufacturer,
            modelName = identity?.model ?: existing?.modelName,
            hostname = identity?.hostname ?: existing?.hostname,
            ippPath = finalIppPath,
            capabilities = identity?.capabilities ?: existing?.capabilities,
        )
        repository.upsert(printer)
        if (isDefault) repository.setDefault(printer.id)
        return printer
    }
}

/** Identidade que a descoberta obteve da própria impressora (IPP/SNMP/mDNS). */
data class DiscoveredIdentity(
    val manufacturer: String?,
    val model: String?,
    val hostname: String?,
    val ippPath: String?,
    val capabilities: br.com.atos.printbridge.domain.model.PrinterCapabilities?,
)

class DeletePrinterUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    suspend operator fun invoke(id: String) = repository.delete(id)
}

class SetDefaultPrinterUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.getById(id) ?: throw PrinterNotFoundException(id)
        repository.setDefault(id)
    }
}

class SetPrinterEnabledUseCase @Inject constructor(
    private val repository: PrinterRepository,
    private val queueDispatcher: QueueDispatcher,
) {
    suspend operator fun invoke(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
        // Com a impressora desativada a fila para nela e não reagenda; ao reativar, os jobs
        // pendentes precisam sair na hora, não no worker periódico (achado no CF550, 29/09/2026).
        if (enabled) queueDispatcher.requestProcessing()
    }
}

class ObservePrintersUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    operator fun invoke() = repository.observeAll()
}

class ObservePrintersByStoreUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    operator fun invoke(storeId: String) = repository.observeByStore(storeId)
}

class MarkPrinterSeenUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.getById(id) ?: throw PrinterNotFoundException(id)
        repository.markSeen(id)
    }
}

class GetDefaultPrinterUseCase @Inject constructor(
    private val repository: PrinterRepository,
) {
    suspend operator fun invoke(): Printer? = repository.getDefault()
}
