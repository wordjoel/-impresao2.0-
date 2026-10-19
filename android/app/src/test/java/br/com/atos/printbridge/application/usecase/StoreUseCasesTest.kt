package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidStoreException
import br.com.atos.printbridge.domain.model.Store
import br.com.atos.printbridge.domain.model.StoreActivationStatus
import br.com.atos.printbridge.domain.model.StoreStatus
import br.com.atos.printbridge.testutil.FakeStoreRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoreUseCasesTest {

    private val repository = FakeStoreRepository()
    private val save = SaveStoreUseCase(repository)
    private val observeAll = ObserveStoresUseCase(repository)
    private val getById = GetStoreUseCase(repository)
    private val delete = DeleteStoreUseCase(repository)

    @Test
    fun `creating a store generates storeId and persists defaults`() = runTest {
        val store = save(storeId = null, storeCode = "4521", storeName = "Loja Centro")

        assertNotNull(store.storeId)
        assertEquals(StoreStatus.UNKNOWN, store.status)
        assertEquals(StoreActivationStatus.PENDING, store.activationStatus)
        assertEquals(store, repository.getById(store.storeId))
    }

    @Test
    fun `updating an existing store preserves createdAt`() = runTest {
        val created = save(storeId = null, storeCode = "4521", storeName = "Loja Centro")

        val updated = save(
            storeId = created.storeId,
            storeCode = "4521",
            storeName = "Loja Centro Renomeada",
        )

        assertEquals(created.storeId, updated.storeId)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals("Loja Centro Renomeada", updated.storeName)
    }

    @Test
    fun `blank store code or name is rejected`() = runTest {
        val blankCodeError = runCatching { save(storeId = null, storeCode = "   ", storeName = "Loja") }.exceptionOrNull()
        val blankNameError = runCatching { save(storeId = null, storeCode = "4521", storeName = "") }.exceptionOrNull()

        assertEquals(InvalidStoreException::class.java, blankCodeError?.javaClass)
        assertEquals(InvalidStoreException::class.java, blankNameError?.javaClass)
    }

    @Test
    fun `observeAll emits stored stores and delete removes them`() = runTest {
        save(storeId = null, storeCode = "4521", storeName = "Loja Centro")
        save(storeId = null, storeCode = "7777", storeName = "Loja Norte")

        val list = observeAll().first()
        assertEquals(2, list.size)

        delete(list.first().storeId)

        assertNull(repository.getById(list.first().storeId))
        assertEquals(1, observeAll().first().size)
    }

    @Test
    fun `getById returns null for unknown store`() = runTest {
        assertNull(getById("não-existe"))
    }
}