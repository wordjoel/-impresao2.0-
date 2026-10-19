package br.com.atos.printbridge.service

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.JobStatus
import br.com.atos.printbridge.api.PrintBridgeClient
import br.com.atos.printbridge.api.PrintBridgeContract
import br.com.atos.printbridge.api.PrintBridgeRemoteException
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.data.local.db.AppDatabase
import br.com.atos.printbridge.domain.template.BuiltInPrintTemplates
import br.com.atos.printbridge.domain.template.PlaceholderTemplateRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Critérios de aceite 1, 2, 3 e 6 da Fase 1, ponta a ponta no aparelho: um cliente faz bind no
 * PrintBridgeService REAL (AIDL), manda template e PDF, e uma "impressora" TCP local (ServerSocket
 * no próprio coletor) recebe os bytes — sem PDF externo, Acrobat, Print Framework ou utilitário.
 *
 * Com impressora física, troque 127.0.0.1/porta pelo IP da TSC para o teste de campo.
 * Pré-requisito: dispositivo com licença ativa (senão o teste é pulado, não reprovado).
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PrintBridgeServiceE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var client: PrintBridgeClient
    private lateinit var printerServer: ServerSocket
    private val received = LinkedBlockingQueue<ByteArray>()
    private val events = Collections.synchronizedList(mutableListOf<Bundle>())
    private var printerId: String? = null

    @Before
    fun setUp() = runBlocking {
        printerServer = ServerSocket(0)
        thread(isDaemon = true) {
            while (!printerServer.isClosed) {
                runCatching { printerServer.accept().use { received.put(it.getInputStream().readBytes()) } }
            }
        }
        client = PrintBridgeClient.connect(context, packageName = context.packageName)
        client.setListener { events += it }
        printerId = client.registerPrinterProfile(
            PrinterProfile(
                id = null, storeId = "loja-e2e", name = "E2E mock 127.0.0.1", ipAddress = "127.0.0.1",
                port = printerServer.localPort, protocol = "ZPL", dpi = 203, defaultPaperSize = "100x72",
            ),
        )
    }

    @After
    fun tearDown() {
        client.close()
        printerServer.close()
        // Não deixa a impressora de teste no cadastro do coletor.
        printerId?.let { id ->
            val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()
            runBlocking { db.printerDao().delete(id) }
            db.close()
        }
    }

    private suspend fun printOrSkip(request: PrintJobRequest): String =
        try {
            client.printJob(request)
        } catch (e: PrintBridgeRemoteException) {
            assumeTrue("Licença não ativa neste aparelho — E2E pulado", e.code != PrintBridgeContract.ERROR_LICENSE_RESTRICTED)
            throw e
        }

    private fun awaitEvent(type: String, timeoutSec: Long = 30): Bundle {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            synchronized(events) { events.firstOrNull { it.getString(PrintBridgeContract.EVENT_TYPE) == type } }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Evento $type não chegou em ${timeoutSec}s. Recebidos: ${events.map { it.getString(PrintBridgeContract.EVENT_TYPE) }}")
    }

    @Test
    fun templateJobReachesThePrinterByteExactAndCallerIsNotified() = runBlocking {
        val data = mapOf(
            "loteSequencia" to "96644", "pesoLiquido" to "10,020 Kg", "pesoBruto" to "10,390 Kg",
            "codigoBarra1" to "7891234567895", "codigoBarra2" to "", "codigoBarra3" to "", "codigoBarra4" to "",
            "qrCodePayload" to "lote-96644",
        )

        val jobId = printOrSkip(
            PrintJobRequest(
                DocumentType.TEMPLATE, templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID, variables = data,
                printerProfileId = printerId, externalDocumentId = "PESAGEM-96644",
            ),
        )

        val bytes = received.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("impressora não recebeu nada")
        assertEquals(PlaceholderTemplateRenderer.render(BuiltInPrintTemplates.SIF_PESAGEM_100X72, data), String(bytes, Charsets.UTF_8))
        val completed = awaitEvent(PrintBridgeContract.EVENT_JOB_COMPLETED)
        assertEquals(jobId, completed.getString(PrintBridgeContract.EVENT_JOB_ID))
        assertEquals("PESAGEM-96644", completed.getString(PrintBridgeContract.EVENT_EXTERNAL_ID))
        assertTrue(client.getJobStatus(jobId) is JobStatus.Completed)
    }

    @Test
    fun missingTemplateVariableIsRefusedWithStableCode() = runBlocking {
        try {
            printOrSkip(PrintJobRequest(DocumentType.TEMPLATE, templateId = BuiltInPrintTemplates.SIF_PESAGEM_100X72_ID, variables = mapOf("loteSequencia" to "1"), printerProfileId = printerId))
            throw AssertionError("deveria ter recusado")
        } catch (e: PrintBridgeRemoteException) {
            assertEquals(PrintBridgeContract.ERROR_MISSING_VARIABLES, e.code)
            assertTrue(e.missingVariables.contains("pesoBruto"))
        }
    }

    @Test
    fun multiPagePdfPrintsEveryPageInOrderAsRasterGraphics() = runBlocking {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
        repeat(2) { i ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(283, 204, i + 1).create()) // 100x72mm em pt
            page.canvas.drawText("Pagina ${i + 1}", 20f, 60f, paint)
            pdf.finishPage(page)
        }
        val bytes = ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()

        val documentId = printOrSkip(PrintJobRequest(DocumentType.PDF, documentBytes = bytes, printerProfileId = printerId))

        val page1 = received.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("página 1 não chegou")
        val page2 = received.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("página 2 não chegou")
        listOf(page1, page2).forEach { assertTrue(String(it, Charsets.UTF_8).contains("^GFA,")) }
        assertTrue("páginas diferentes", !page1.contentEquals(page2))
        val finished = awaitEvent(PrintBridgeContract.EVENT_DOCUMENT_FINISHED)
        assertEquals(2, finished.getInt(PrintBridgeContract.EVENT_PAGES_OK))
        val pages = synchronized(events) {
            events.filter { it.getString(PrintBridgeContract.EVENT_TYPE) == PrintBridgeContract.EVENT_PAGE_PRINTED }
                .map { it.getInt(PrintBridgeContract.EVENT_PAGE) }
        }
        assertEquals(listOf(1, 2), pages)
        assertTrue(client.getJobStatus(documentId) is JobStatus.Completed)
    }
}
