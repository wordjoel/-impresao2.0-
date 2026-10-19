package br.com.atos.printbridge.service

import android.os.Build
import br.com.atos.printbridge.api.DitherMode
import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.JobStatus
import br.com.atos.printbridge.api.PrintBridgeContract
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** O formato em Bundle é o contrato com os apps clientes: se isto quebrar, quebra a integração deles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PrintBridgeContractTest {

    @Test
    fun `print request survives the bundle round trip`() {
        val request = PrintJobRequest(
            documentType = DocumentType.PDF,
            documentBytes = byteArrayOf(1, 2, 3),
            printerProfileId = "p1",
            documentName = "Nota",
            externalDocumentId = "NF-123",
            copies = 2,
            ditherMode = DitherMode.FLOYD_STEINBERG,
        )

        val back = PrintJobRequest.fromBundle(request.toBundle())

        assertEquals(DocumentType.PDF, back.documentType)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.documentBytes)
        assertEquals(listOf("p1", "Nota", "NF-123"), listOf(back.printerProfileId, back.documentName, back.externalDocumentId))
        assertEquals(2, back.copies)
        assertEquals(DitherMode.FLOYD_STEINBERG, back.ditherMode)
    }

    @Test
    fun `template variables survive the bundle round trip`() {
        val back = PrintJobRequest.fromBundle(
            PrintJobRequest(DocumentType.TEMPLATE, templateId = "sif", variables = mapOf("pesoLiquido" to "10,020 Kg", "vazio" to "")).toBundle(),
        )
        assertEquals(mapOf("pesoLiquido" to "10,020 Kg", "vazio" to ""), back.variables)
    }

    @Test
    fun `every job status survives the bundle round trip`() {
        listOf(
            JobStatus.Queued(3), JobStatus.Sending(2), JobStatus.Completed(1234), JobStatus.Failed("Conexão recusada", 5),
            JobStatus.PartiallyCompleted(2, 3), JobStatus.Cancelled,
        ).forEach { assertEquals(it, JobStatus.fromBundle(it.toBundle())) }
    }

    @Test
    fun `printer profile survives the bundle round trip and paper size parses`() {
        val profile = PrinterProfile("p1", "loja-1", "TSC", "10.0.0.5", 9100, "TSPL", 300, "100x72", true)
        assertEquals(profile, PrinterProfile.fromBundle(profile.toBundle()))
        assertEquals(100 to 72, PrinterProfile.parsePaperSize(" 100 X 72 "))
        assertEquals(null, PrinterProfile.parsePaperSize("100mm"))
    }

    @Test
    fun `business error becomes stable code plus the missing variable list`() {
        val result = PrintBridgeResults.error(MissingTemplateVariablesException("sif", listOf("pesoBruto")))

        assertFalse(result.getBoolean(PrintBridgeContract.RESULT_OK))
        assertEquals(PrintBridgeContract.ERROR_MISSING_VARIABLES, result.getString(PrintBridgeContract.RESULT_ERROR_CODE))
        assertEquals(arrayListOf("pesoBruto"), result.getStringArrayList("missingVariables"))
    }

    @Test
    fun `unexpected exception never leaks its internal message across the binder`() {
        val result = PrintBridgeResults.error(IllegalStateException("SQLiteException at /data/data/br.com.atos/..."))

        assertEquals(PrintBridgeContract.ERROR_INTERNAL, result.getString(PrintBridgeContract.RESULT_ERROR_CODE))
        assertFalse(result.getString(PrintBridgeContract.RESULT_ERROR_MESSAGE)!!.contains("/data/"))
    }

    @Test
    fun `events carry the names promised in the master prompt`() {
        fun type(e: PrintJobEvent) = PrintBridgeResults.eventBundle(e, "NF-1").getString(PrintBridgeContract.EVENT_TYPE)

        assertEquals("job-completed", type(PrintJobEvent.Completed("j", null, 10, 1)))
        assertEquals("job-failed", type(PrintJobEvent.Failed("j", null, "x", 5)))
        assertEquals("job-retrying", type(PrintJobEvent.Retrying("j", null, 1, 5, "x", 2000)))
        assertEquals("page-x-of-n-printed", type(PrintJobEvent.PagePrinted("j", "g", 2, 3)))
        val page = PrintBridgeResults.eventBundle(PrintJobEvent.PagePrinted("j", "g", 2, 3), "NF-1")
        assertEquals(listOf(2, 3), listOf(page.getInt(PrintBridgeContract.EVENT_PAGE), page.getInt(PrintBridgeContract.EVENT_PAGE_COUNT)))
        assertEquals("NF-1", page.getString(PrintBridgeContract.EVENT_EXTERNAL_ID))
    }
}
