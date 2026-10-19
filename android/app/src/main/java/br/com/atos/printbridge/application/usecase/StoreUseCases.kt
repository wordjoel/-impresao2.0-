package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidStoreException
import br.com.atos.printbridge.domain.model.Store
import br.com.atos.printbridge.domain.model.StoreActivationStatus
import br.com.atos.printbridge.domain.model.StoreStatus
import br.com.atos.printbridge.domain.repository.StoreRepository
import java.util.UUID
import javax.inject.Inject

class SaveStoreUseCase @Inject constructor(
    private val repository: StoreRepository,
) {
    /**
     * Cria/atualiza uma loja na instalação. `storeId` é gerado localmente na criação (persistido
     * localmente até existir backend real — Fase 7); `status`/`activationStatus` começam em
     * UNKNOWN/PENDING enquanto não há avaliação nem ativação.
     */
    suspend operator fun invoke(
        storeId: String?,
        storeCode: String,
        storeName: String,
        status: StoreStatus = StoreStatus.UNKNOWN,
        activationStatus: StoreActivationStatus = StoreActivationStatus.PENDING,
    ): Store {
        val trimmedCode = storeCode.trim()
        val trimmedName = storeName.trim()
        if (trimmedCode.isBlank()) {
            throw InvalidStoreException("Código da loja não pode ser vazio")
        }
        if (trimmedName.isBlank()) {
            throw InvalidStoreException("Nome da loja não pode ser vazio")
        }
        val now = System.currentTimeMillis()
        val existing = storeId?.let { repository.getById(it) }
        val store = Store(
            storeId = storeId ?: UUID.randomUUID().toString(),
            storeCode = trimmedCode,
            storeName = trimmedName,
            status = status,
            activationStatus = activationStatus,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        repository.upsert(store)
        return store
    }
}

class DeleteStoreUseCase @Inject constructor(
    private val repository: StoreRepository,
) {
    suspend operator fun invoke(storeId: String) = repository.delete(storeId)
}

class ObserveStoresUseCase @Inject constructor(
    private val repository: StoreRepository,
) {
    operator fun invoke() = repository.observeAll()
}

class GetStoreUseCase @Inject constructor(
    private val repository: StoreRepository,
) {
    suspend operator fun invoke(storeId: String): Store? = repository.getById(storeId)
}