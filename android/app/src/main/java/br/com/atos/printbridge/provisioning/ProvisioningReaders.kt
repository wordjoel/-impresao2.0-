package br.com.atos.printbridge.provisioning

import android.os.Bundle
import android.os.Parcelable
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.provisioning.ProvisionedPrinter
import br.com.atos.printbridge.domain.provisioning.ProvisioningConfig
import org.json.JSONObject

/**
 * Converte as duas origens de instalação em escala para [ProvisioningConfig]. As chaves são as
 * mesmas nas duas (ver res/xml/app_restrictions.xml): o que um administrador configura no MDM é
 * exatamente o que a bancada escreve no provisioning.json.
 */
object ProvisioningReaders {

    /** Managed Configurations entregues pelo MDM/EMM (RestrictionsManager.applicationRestrictions). */
    @Suppress("DEPRECATION") // getParcelableArray(key, Class) só existe a partir da API 33.
    fun fromManagedConfig(bundle: Bundle): ProvisioningConfig = ProvisioningConfig(
        storeCode = bundle.getString("store_code")?.trim()?.ifEmpty { null },
        storeName = bundle.getString("store_name")?.trim()?.ifEmpty { null },
        printers = (bundle.getParcelableArray("printers") ?: emptyArray<Parcelable>())
            .filterIsInstance<Bundle>()
            .map { p ->
                printer(
                    name = p.getString("name"),
                    ip = p.getString("ip"),
                    port = if (p.containsKey("port")) p.getInt("port") else null,
                    language = p.getString("language"),
                    model = p.getString("model"),
                    dpi = p.getString("dpi")?.toIntOrNull() ?: if (p.containsKey("dpi")) p.getInt("dpi") else null,
                    paper = p.getString("paper"),
                    isDefault = p.getBoolean("is_default", false),
                )
            },
        replacePrinters = bundle.getBoolean("replace_printers", false),
        maxAttempts = bundle.intOrNull("max_attempts"),
        retryBaseIntervalMillis = bundle.intOrNull("retry_base_ms")?.toLong(),
        historyRetentionDays = bundle.intOrNull("history_retention_days"),
        adminPin = bundle.getString("admin_pin")?.trim()?.ifEmpty { null },
    )

    /** provisioning.json da bancada (mesmas chaves). @throws org.json.JSONException JSON inválido. */
    fun fromJson(text: String): ProvisioningConfig {
        val o = JSONObject(text)
        val printers = o.optJSONArray("printers")
        return ProvisioningConfig(
            storeCode = o.optString("store_code").trim().ifEmpty { null },
            storeName = o.optString("store_name").trim().ifEmpty { null },
            printers = (0 until (printers?.length() ?: 0)).map { i ->
                val p = printers!!.getJSONObject(i)
                printer(
                    name = p.optString("name"),
                    ip = p.optString("ip"),
                    port = if (p.has("port")) p.getInt("port") else null,
                    language = p.optString("language").ifEmpty { null },
                    model = p.optString("model").ifEmpty { null },
                    dpi = if (p.has("dpi")) p.getInt("dpi") else null,
                    paper = p.optString("paper").ifEmpty { null },
                    isDefault = p.optBoolean("is_default", false),
                )
            },
            replacePrinters = o.optBoolean("replace_printers", false),
            maxAttempts = if (o.has("max_attempts")) o.getInt("max_attempts") else null,
            retryBaseIntervalMillis = if (o.has("retry_base_ms")) o.getLong("retry_base_ms") else null,
            historyRetentionDays = if (o.has("history_retention_days")) o.getInt("history_retention_days") else null,
            adminPin = o.optString("admin_pin").trim().ifEmpty { null },
        )
    }

    private fun printer(
        name: String?,
        ip: String?,
        port: Int?,
        language: String?,
        model: String?,
        dpi: Int?,
        paper: String?,
        isDefault: Boolean,
    ) = ProvisionedPrinter(
        name = name?.trim().orEmpty(),
        ipAddress = ip?.trim().orEmpty(),
        port = port ?: 9100,
        // Valor desconhecido vira AUTO/erro de validação explícito, nunca um default silencioso.
        language = language?.let { l -> PrinterLanguage.entries.firstOrNull { it.name.equals(l.trim(), true) } ?: PrinterLanguage.AUTO }
            ?: PrinterLanguage.ZPL,
        model = model?.let { m -> PrinterModel.entries.firstOrNull { it.name.equals(m.trim(), true) } } ?: PrinterModel.TSC_ML241,
        dpi = dpi ?: 203,
        paperSize = paper?.trim()?.ifEmpty { null } ?: "100x72",
        isDefault = isDefault,
    )

    private fun Bundle.intOrNull(key: String): Int? = if (containsKey(key)) getInt(key) else null
}
