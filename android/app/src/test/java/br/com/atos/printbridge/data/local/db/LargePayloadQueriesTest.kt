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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressão do crash no CF550 (29/09/2026): `IllegalStateException: Couldn't read row 1, col 0 from
 * CursorWindow` ao imprimir um PDF de 3 páginas 100x150 a 300 dpi com a tela da fila aberta. Cada
 * página vira um BITMAP TSPL de ~260 KB (bytes altos → ~520 KB em UTF-8 no banco), e as consultas de
 * lista/status traziam o payload de todas as linhas para um CursorWindow de 2 MB.
 *
 * O Robolectric não aplica o limite de 2 MB do CursorWindow (medido: as consultas antigas passam
 * aqui e quebram no CF550), então o teste fixa o contrato da correção, "lista/status não trazem o
 * payload"; a prova do crash é o roteiro de fumaça no coletor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LargePayloadQueriesTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // abre já, para o insert da impressora não disputar o primeiro acesso
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Página TSPL 100x150 a 300 dpi: 1181x1771 px → 148 bytes/linha x 1771 linhas, quase tudo 0xFF. */
    private val tsplPage: String = buildString {
        append("SIZE 100 mm,150 mm\r\nCLS\r\nBITMAP 0,0,148,1771,0,")
        append("ÿ".repeat(148 * 1771))
        append("\r\nPRINT 1\r\n")
    }

    private fun page(index: Int, status: String, groupId: String = "doc-1", pageCount: Int = PAGES) = PrintJobEntity(
        id = "$groupId-p$index",
        printerId = "p1",
        type = "TSPL",
        payload = tsplPage,
        documentName = "PDF ($index/$pageCount)",
        copies = 1,
        status = status,
        attempts = 0,
        maxAttempts = 5,
        errorMessage = null,
        externalDocumentId = null,
        originalJobId = null,
        createdAt = 1_000,
        startedAt = null,
        sentAt = null,
        updatedAt = 1_000L + index,
        validationWarnings = null,
        groupId = groupId,
        pageIndex = index,
        pageCount = pageCount,
    )

    private suspend fun insertDocument(status: (Int) -> String) {
        db.printerDao().upsert(
            PrinterEntity(
                id = "p1", storeId = null, name = "TSC", model = "TSC_ML241", ipAddress = "127.0.0.1", port = 9105,
                protocol = "TCP", language = "TSPL", isDefault = false, enabled = true, lastSeenAt = null,
                createdAt = 0, updatedAt = 0,
            )
        )
        db.printJobDao().insertAll((1..PAGES).map { page(it, status(it)) })
    }

    @Test
    fun `fila ativa nao carrega o payload das paginas`() = runTest {
        insertDocument { if (it == 1) "PROCESSING" else "PENDING" }

        val active = db.printJobDao().observeActive().first()

        assertEquals(PAGES, active.size)
        assertTrue("lista não carrega o conteúdo da etiqueta", active.all { it.payload.isEmpty() })
    }

    @Test
    fun `historico nao carrega o payload das paginas`() = runTest {
        insertDocument { "SENT" }

        val history = db.printJobDao().observeHistory().first()

        assertEquals(PAGES, history.size)
        assertTrue(history.all { it.payload.isEmpty() })
    }

    @Test
    fun `status de documento multipagina nao carrega o payload e mantem a ordem`() = runTest {
        insertDocument { if (it <= 3) "SENT" else "PENDING" }

        val pages = db.printJobDao().getByGroupId("doc-1")

        assertEquals((1..PAGES).toList(), pages.map { it.pageIndex })
        assertEquals(3, pages.count { it.status == "SENT" })
        assertEquals(PAGES, pages.first().pageCount)
        assertTrue(pages.all { it.payload.isEmpty() })
    }

    @Test
    fun `envio continua lendo o payload completo da pagina`() = runTest {
        insertDocument { "PENDING" }

        val next = db.printJobDao().nextPendingFor("p1")
        val byId = db.printJobDao().getById("doc-1-p2")

        assertEquals("doc-1-p1", next?.id)
        assertEquals(tsplPage, next?.payload)
        assertEquals(tsplPage, byId?.payload)
    }

    private companion object {
        const val PAGES = 10
    }
}
