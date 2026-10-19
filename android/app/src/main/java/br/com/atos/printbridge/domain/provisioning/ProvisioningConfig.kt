package br.com.atos.printbridge.domain.provisioning

import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel

/**
 * Configuração de instalação em escala (loja + impressoras + ajustes operacionais), aplicada sem
 * ninguém tocar no coletor. Mesmo formato para as duas origens:
 *  - MDM/EMM: Managed Configurations do Android Enterprise (res/xml/app_restrictions.xml);
 *  - bancada: arquivo provisioning.json empurrado por ADB (tools/instalacao-em-escala).
 *
 * Nunca carrega licença/token nem chave: a ativação em escala é o PairingCode/QR contra o backend
 * (CARREFOUR-PRINT-PROVISIONING.md), que depende do backend de produção.
 */
data class ProvisioningConfig(
    val storeCode: String?,
    val storeName: String?,
    val printers: List<ProvisionedPrinter> = emptyList(),
    /** true = impressoras desta loja que não estão na configuração são removidas (MDM é a fonte da verdade). */
    val replacePrinters: Boolean = false,
    val maxAttempts: Int? = null,
    val retryBaseIntervalMillis: Long? = null,
    val historyRetentionDays: Int? = null,
    /** PIN do modo administrador, em texto — hasheado na hora (PinHasher), nunca persistido puro. */
    val adminPin: String? = null,
) {
    val isEmpty: Boolean
        get() = storeCode.isNullOrBlank() && printers.isEmpty() && maxAttempts == null &&
            retryBaseIntervalMillis == null && historyRetentionDays == null && adminPin.isNullOrBlank()

    /**
     * Impressão digital estável da configuração: o app só reaplica quando ela muda (evita reescrever
     * o banco a cada abertura). O PIN entra por hash simples para não ficar em claro na impressão.
     */
    fun fingerprint(): String = listOf(
        storeCode, storeName, replacePrinters, maxAttempts, retryBaseIntervalMillis, historyRetentionDays,
        adminPin?.hashCode(),
        printers.joinToString(";") { "${it.name}|${it.ipAddress}|${it.port}|${it.language}|${it.model}|${it.dpi}|${it.paperSize}|${it.isDefault}" },
    ).joinToString("#").hashCode().toUInt().toString(16)
}

data class ProvisionedPrinter(
    val name: String,
    val ipAddress: String,
    val port: Int = 9100,
    val language: PrinterLanguage = PrinterLanguage.ZPL,
    val model: PrinterModel = PrinterModel.TSC_ML241,
    val dpi: Int = 203,
    /** "LARGURAxALTURA" em mm. */
    val paperSize: String = "100x72",
    val isDefault: Boolean = false,
)

data class ProvisioningResult(
    val applied: Boolean,
    val storeId: String? = null,
    val printersCreated: Int = 0,
    val printersUpdated: Int = 0,
    val printersRemoved: Int = 0,
    /** Motivos de recusa — com erro, NADA é aplicado (estado previsível em 5.800 coletores). */
    val errors: List<String> = emptyList(),
) {
    val summary: String
        get() = if (applied) {
            "aplicada: loja=${storeId ?: "-"} impressoras +$printersCreated ~$printersUpdated -$printersRemoved"
        } else {
            "recusada: ${errors.joinToString("; ")}"
        }
}
