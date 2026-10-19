package br.com.atos.printbridge.domain.repository

import br.com.atos.printbridge.domain.model.LicenseState
import kotlinx.coroutines.flow.Flow

interface LicenseRepository {
    fun observe(): Flow<LicenseState>
    suspend fun current(): LicenseState
    suspend fun update(transform: (LicenseState) -> LicenseState)
}
