package br.com.atos.printbridge.data.local.db

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.atos.printbridge.data.local.entity.PrintJobEntity
import br.com.atos.printbridge.data.local.entity.PrinterEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Consultas do estado para o MDM (item 2) contra o SQLite real do Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PrintStatusQueriesTest {
    private lateinit var db: AppDatabase
    private val since = 10_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() = db.close()

    private suspend fun printer(id: String) = db.printerDao().upsert(
        PrinterEntity(
            id = id, storeId = null, name = "TSC $id", model = "TSC_MB241", ipAddress = "10.0.0.$id", port = 9100,
            protocol = "TCP", language = "TSPL", isDefault = false, enabled = true, lastSeenAt = null,
            createdAt = 0, updatedAt = 0,
        )
    )

    private fun job(id: String, printerId: String, status: String, createdAt: Long, updatedAt: Long, sentAt: Long? = null, error: String? = null) =
        PrintJobEntity(
            id = id, printerId = printerId, type = "TSPL", payload = "PRINT 1", documentName = id, copies = 1,
            status = status, attempts = 0, maxAttempts = 5, errorMessage = error, externalDocumentId = null,
            originalJobId = null, createdAt = createdAt, startedAt = null, sentAt = sentAt, updatedAt = updatedAt,
            validationWarnings = null, groupId = null, pageIndex = null, pageCount = null,
        )

    @Test
    fun `banco vazio - contagens zero, sem nulos que derrubem o mapeamento`() = runTest {
        val q = db.printJobDao().queueSummary(since)
        assertEquals(0, q.pending)
        assertEquals(0, q.processing)
        assertNull(q.oldestPendingAt)
        assertEquals(0, q.failedSince)
        assertEquals(0, q.sentSince)
        assertEquals(emptyList<Any>(), db.printJobDao().printerActivity())
    }

    @Test
    fun `resumo da fila, atividade por impressora e ultimas falhas`() = runTest {
        printer("1")
        printer("2")
        db.printJobDao().insertAll(
            listOf(
                job("a", "1", "PENDING", createdAt = 12_000, updatedAt = 12_000),
                job("b", "1", "PENDING", createdAt = 11_000, updatedAt = 11_000),
                job("c", "1", "PROCESSING", createdAt = 13_000, updatedAt = 13_000),
                job("d", "1", "SENT", createdAt = 1_000, updatedAt = 15_000, sentAt = 14_000),
                job("e", "1", "FAILED", createdAt = 1_000, updatedAt = 16_000, error = "timeout"),
                job("f", "1", "FAILED", createdAt = 1_000, updatedAt = 9_000, error = "fora da janela"),
                job("g", "2", "SENT", createdAt = 1_000, updatedAt = 5_000, sentAt = 5_000),
                job("h", "2", "CANCELLED", createdAt = 1_000, updatedAt = 17_000),
            )
        )
        val q = db.printJobDao().queueSummary(since)
        assertEquals(2, q.pending)
        assertEquals(1, q.processing)
        assertEquals(11_000L, q.oldestPendingAt)
        assertEquals(1, q.failedSince)   // "f" e anterior a janela
        assertEquals(1, q.sentSince)     // "g" e anterior a janela

        val byPrinter = db.printJobDao().printerActivity().associateBy { it.printerId }
        assertEquals(3, byPrinter.getValue("1").activeJobs)
        assertEquals(14_000L, byPrinter.getValue("1").lastSentAt)
        assertEquals(16_000L, byPrinter.getValue("1").lastFailedAt)
        assertEquals(0, byPrinter.getValue("2").activeJobs)
        assertNull(byPrinter.getValue("2").lastFailedAt)

        val failures = db.printJobDao().recentFailures(since)
        assertEquals(listOf("timeout"), failures.map { it.errorMessage })
        assertEquals(17_000L, db.printJobDao().observeLastChange().first())
    }
}
