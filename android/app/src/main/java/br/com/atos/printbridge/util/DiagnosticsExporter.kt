package br.com.atos.printbridge.util

import android.content.Context
import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.data.local.db.AppDatabase
import br.com.atos.printbridge.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Gera um pacote de diagnóstico local (versão, config técnica sem segredos, últimos eventos)
 * para suporte remoto ao cliente — nunca inclui PIN/hash ou payloads ZPL completos.
 */
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun export(settings: AppSettings, networkAvailable: Boolean): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "diagnostico_atos_print_bridge_$timestamp.txt")
        val builder = StringBuilder()
        builder.appendLine("Atos Print Bridge — Pacote de Diagnóstico")
        builder.appendLine("Gerado em: ${Date()}")
        builder.appendLine()
        builder.appendLine("Versão do app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        builder.appendLine("Versão do banco local (Room): ${AppDatabase.DATABASE_NAME} v1")
        builder.appendLine("Rede disponível: $networkAvailable")
        builder.appendLine()
        builder.appendLine("Configuração técnica:")
        builder.appendLine("  maxAttempts=${settings.maxAttempts}")
        builder.appendLine("  connectTimeoutMillis=${settings.connectTimeoutMillis}")
        builder.appendLine("  socketTimeoutMillis=${settings.socketTimeoutMillis}")
        builder.appendLine("  retryBaseIntervalMillis=${settings.retryBaseIntervalMillis}")
        builder.appendLine("  autoRetryTransientFailures=${settings.autoRetryTransientFailures}")
        builder.appendLine("  historyRetentionDays=${settings.historyRetentionDays}")
        builder.appendLine("  adminPinConfigured=${settings.adminPinHash != null}")
        builder.appendLine()
        builder.appendLine("Últimos eventos (mais recente por último):")
        Logger.recentEvents().forEach { builder.appendLine("  $it") }

        file.writeText(builder.toString())
        return file
    }
}
