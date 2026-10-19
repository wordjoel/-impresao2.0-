package br.com.atos.printbridge.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.repository.LicenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private object LicenseKeys {
    val RAW_TOKEN = stringPreferencesKey("license_raw_token")
}

/**
 * Este DataStore continua tão editável por qualquer processo com acesso ao armazenamento do app
 * quanto qualquer outra preferência — isso deixou de ser um problema porque o único campo
 * persistido (rawToken) é um token assinado pelo servidor: editar o valor só produz um token com
 * assinatura inválida, que EvaluateLicenseStatusUseCase rejeita (RESTRICTED), nunca um valor
 * forjado aceito como válido. Ver CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 */
class LicenseRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LicenseRepository {

    override fun observe(): Flow<LicenseState> = dataStore.data.map { it.toLicenseState() }

    override suspend fun current(): LicenseState = dataStore.data.map { it.toLicenseState() }.first()

    override suspend fun update(transform: (LicenseState) -> LicenseState) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toLicenseState())
            if (next.rawToken != null) {
                prefs[LicenseKeys.RAW_TOKEN] = next.rawToken
            } else {
                prefs.remove(LicenseKeys.RAW_TOKEN)
            }
        }
    }

    private fun Preferences.toLicenseState(): LicenseState =
        LicenseState(rawToken = this[LicenseKeys.RAW_TOKEN])
}
