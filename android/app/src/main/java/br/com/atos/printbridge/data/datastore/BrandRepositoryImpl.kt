package br.com.atos.printbridge.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.domain.repository.BrandRepository
import br.com.atos.printbridge.ui.brand.resolveBrandKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val BRAND_KEY = stringPreferencesKey("brand_key")

/**
 * Reaproveita o mesmo `DataStore<Preferences>` das configurações (`atos_print_bridge_settings`):
 * uma preferência de marca não justifica um arquivo próprio (Fase 9.13, simplicidade).
 */
class BrandRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : BrandRepository {

    override fun observeBrandKey(): Flow<String> =
        dataStore.data.map { resolveBrandKey(it[BRAND_KEY], BuildConfig.BRAND_LOCKED) }

    override suspend fun currentBrandKey(): String = observeBrandKey().first()

    override suspend fun setBrandKey(key: String) {
        // APK de cliente: a marca vem do flavor; a tela nem oferece a troca (SettingsScreen).
        if (BuildConfig.BRAND_LOCKED) return
        dataStore.edit { it[BRAND_KEY] = key }
    }
}
