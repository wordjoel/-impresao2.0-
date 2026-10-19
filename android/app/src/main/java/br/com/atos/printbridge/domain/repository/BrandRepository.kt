package br.com.atos.printbridge.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Marca (identidade visual) selecionada no dispositivo. É uma preferência de configuração, não um
 * dado de impressão: persiste em DataStore junto das demais configurações e é lida pelo tema.
 */
interface BrandRepository {

    fun observeBrandKey(): Flow<String>

    suspend fun currentBrandKey(): String

    suspend fun setBrandKey(key: String)
}
