package br.com.atos.printbridge.application.provisioning

import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.application.usecase.SavePrinterUseCase
import br.com.atos.printbridge.application.usecase.SaveStoreUseCase
import br.com.atos.printbridge.application.usecase.SetAdminPinUseCase
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.provisioning.ProvisioningConfig
import br.com.atos.printbridge.domain.provisioning.ProvisioningResult
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.repository.StoreRepository
import br.com.atos.printbridge.domain.validation.NetworkAddressValidator
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Aplica uma [ProvisioningConfig] de forma idempotente:
 *  - loja: casada pelo código (reaplicar não cria outra);
 *  - impressoras: casadas por IP:porta (reaplicar atualiza, nunca duplica);
 *  - no máximo uma padrão; se só houver uma impressora, ela vira a padrão;
 *  - ajustes (tentativas, backoff, retenção, PIN) só quando informados.
 *
 * Tudo é validado ANTES de gravar qualquer coisa: com um erro, nada muda. Em 5.800 coletores,
 * "meio aplicado" seria o pior estado possível para diagnosticar.
 */
class ApplyProvisioningUseCase @Inject constructor(
    private val storeRepository: StoreRepository,
    private val saveStore: SaveStoreUseCase,
    private val printerRepository: PrinterRepository,
    private val savePrinter: SavePrinterUseCase,
    private val settingsRepository: SettingsRepository,
    private val setAdminPin: SetAdminPinUseCase,
) {
    suspend operator fun invoke(config: ProvisioningConfig, source: String): ProvisioningResult {
        val errors = validate(config)
        if (errors.isNotEmpty()) {
            val result = ProvisioningResult(applied = false, errors = errors)
            Logger.event(LogLevel.WARN, "provisioning_rejected", extra = mapOf("source" to source, "errors" to errors))
            return result
        }

        val storeId = config.storeCode?.takeIf { it.isNotBlank() }?.let { code ->
            val existing = storeRepository.getByCode(code.trim())
            saveStore(
                storeId = existing?.storeId,
                storeCode = code,
                storeName = config.storeName?.takeIf { it.isNotBlank() } ?: existing?.storeName ?: "Loja $code",
                status = existing?.status ?: br.com.atos.printbridge.domain.model.StoreStatus.UNKNOWN,
                activationStatus = existing?.activationStatus ?: br.com.atos.printbridge.domain.model.StoreActivationStatus.PENDING,
            ).storeId
        }

        val existingPrinters = printerRepository.observeAll().first()
        val onlyOne = config.printers.size == 1
        var created = 0
        var updated = 0
        val kept = mutableSetOf<String>()
        config.printers.forEach { p ->
            val (width, height) = PrinterProfile.parsePaperSize(p.paperSize)!!
            val match = existingPrinters.firstOrNull { it.ipAddress == p.ipAddress.trim() && it.port == p.port }
            val saved = savePrinter(
                id = match?.id,
                storeId = storeId ?: match?.storeId,
                name = p.name,
                model = p.model,
                ipAddress = p.ipAddress,
                port = p.port,
                language = p.language,
                isDefault = p.isDefault || onlyOne,
                enabled = match?.enabled ?: true,
                dpi = p.dpi,
                labelWidthMm = width,
                labelHeightMm = height,
            )
            kept += saved.id
            if (match == null) created++ else updated++
        }

        var removed = 0
        if (config.replacePrinters && storeId != null) {
            existingPrinters.filter { it.storeId == storeId && it.id !in kept }.forEach {
                printerRepository.delete(it.id)
                removed++
            }
        }

        if (config.maxAttempts != null || config.retryBaseIntervalMillis != null || config.historyRetentionDays != null) {
            settingsRepository.update { s ->
                s.copy(
                    maxAttempts = config.maxAttempts ?: s.maxAttempts,
                    retryBaseIntervalMillis = config.retryBaseIntervalMillis ?: s.retryBaseIntervalMillis,
                    historyRetentionDays = config.historyRetentionDays ?: s.historyRetentionDays,
                )
            }
        }
        config.adminPin?.takeIf { it.isNotBlank() }?.let { setAdminPin(it) }

        val result = ProvisioningResult(true, storeId, created, updated, removed)
        Logger.event(
            LogLevel.INFO,
            "provisioning_applied",
            extra = mapOf("source" to source, "storeId" to storeId, "created" to created, "updated" to updated, "removed" to removed),
        )
        return result
    }

    private fun validate(config: ProvisioningConfig): List<String> {
        val errors = mutableListOf<String>()
        if (!config.storeCode.isNullOrBlank() && config.storeCode.trim().length > 32) errors += "store_code longo demais"
        config.printers.forEachIndexed { i, p ->
            val tag = "impressora ${i + 1} (${p.name.ifBlank { p.ipAddress }})"
            if (p.name.isBlank()) errors += "$tag: nome vazio"
            if (!NetworkAddressValidator.isValidAddress(p.ipAddress.trim())) errors += "$tag: IP inválido '${p.ipAddress}'"
            if (!NetworkAddressValidator.isValidPort(p.port)) errors += "$tag: porta inválida ${p.port}"
            if (p.language == PrinterLanguage.AUTO) errors += "$tag: linguagem deve ser ZPL ou TSPL"
            if (p.dpi !in Printer.SUPPORTED_DPI) errors += "$tag: DPI ${p.dpi} (use 203 ou 300)"
            val paper = PrinterProfile.parsePaperSize(p.paperSize)
            if (paper == null || paper.first !in 10..118 || paper.second !in 5..1000) errors += "$tag: papel inválido '${p.paperSize}'"
        }
        val ids = config.printers.map { it.ipAddress.trim() to it.port }
        if (ids.size != ids.toSet().size) errors += "impressoras repetidas (mesmo IP:porta)"
        if (config.printers.count { it.isDefault } > 1) errors += "mais de uma impressora marcada como padrão"
        config.maxAttempts?.let { if (it !in 1..20) errors += "max_attempts fora de 1..20" }
        config.retryBaseIntervalMillis?.let { if (it !in 0..60_000) errors += "retry_base_ms fora de 0..60000" }
        config.historyRetentionDays?.let { if (it !in 0..365) errors += "history_retention_days fora de 0..365" }
        config.adminPin?.takeIf { it.isNotBlank() }?.let { if (it.length !in 4..8 || !it.all(Char::isDigit)) errors += "admin_pin deve ter 4 a 8 dígitos" }
        return errors
    }
}
