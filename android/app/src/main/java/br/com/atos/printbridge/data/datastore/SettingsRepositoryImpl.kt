package br.com.atos.printbridge.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private object Keys {
    val MAX_ATTEMPTS = intPreferencesKey("max_attempts")
    val CONNECT_TIMEOUT = intPreferencesKey("connect_timeout_ms")
    val SOCKET_TIMEOUT = intPreferencesKey("socket_timeout_ms")
    val RETRY_BASE_INTERVAL = longPreferencesKey("retry_base_interval_ms")
    val AUTO_RETRY = booleanPreferencesKey("auto_retry_transient")
    val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
    val ADMIN_PIN_HASH = stringPreferencesKey("admin_pin_hash")
    val DISCOVERY_PORT = intPreferencesKey("discovery_port")
}

class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun current(): AppSettings = dataStore.data.map { it.toAppSettings() }.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = prefs.toAppSettings()
            val next = transform(current)
            prefs[Keys.MAX_ATTEMPTS] = next.maxAttempts
            prefs[Keys.CONNECT_TIMEOUT] = next.connectTimeoutMillis
            prefs[Keys.SOCKET_TIMEOUT] = next.socketTimeoutMillis
            prefs[Keys.RETRY_BASE_INTERVAL] = next.retryBaseIntervalMillis
            prefs[Keys.AUTO_RETRY] = next.autoRetryTransientFailures
            prefs[Keys.HISTORY_RETENTION_DAYS] = next.historyRetentionDays
            prefs[Keys.DISCOVERY_PORT] = next.discoveryPort
            if (next.adminPinHash != null) {
                prefs[Keys.ADMIN_PIN_HASH] = next.adminPinHash
            } else {
                prefs.remove(Keys.ADMIN_PIN_HASH)
            }
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            maxAttempts = this[Keys.MAX_ATTEMPTS] ?: defaults.maxAttempts,
            connectTimeoutMillis = this[Keys.CONNECT_TIMEOUT] ?: defaults.connectTimeoutMillis,
            socketTimeoutMillis = this[Keys.SOCKET_TIMEOUT] ?: defaults.socketTimeoutMillis,
            retryBaseIntervalMillis = this[Keys.RETRY_BASE_INTERVAL] ?: defaults.retryBaseIntervalMillis,
            autoRetryTransientFailures = this[Keys.AUTO_RETRY] ?: defaults.autoRetryTransientFailures,
            historyRetentionDays = this[Keys.HISTORY_RETENTION_DAYS] ?: defaults.historyRetentionDays,
            adminPinHash = this[Keys.ADMIN_PIN_HASH],
            discoveryPort = this[Keys.DISCOVERY_PORT] ?: defaults.discoveryPort,
        )
    }
}
