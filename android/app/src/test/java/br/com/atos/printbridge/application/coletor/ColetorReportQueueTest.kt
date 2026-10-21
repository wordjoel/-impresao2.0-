package br.com.atos.printbridge.application.coletor

import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheckResult
import br.com.atos.printbridge.domain.coletor.ColetorPrintReport
import br.com.atos.printbridge.domain.coletor.StrictJson
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColetorReportQueueTest {

    private val printer = Printer(
        id = "p1", storeId = null, name = "MB241 \"balcão\"", model = PrinterModel.TSC_MB241, ipAddress = "10.0.0.5", port = 9100,
        protocol = PrinterProtocol.TCP, language = PrinterLanguage.TSPL, isDefault = true, enabled = true,
        lastSeenAt = null, createdAt = 0, updatedAt = 0,
    )
    private val label = DesignerLabel(
        payload = "x", type = PrintPayloadType.TSPL, documentName = "d", templateVersion = 7, packageSha256 = "a".repeat(64),
        versionCheck = VersionCheckResult.OFFLINE_GRACE, labelSha256 = "b".repeat(64), target = "TSPL/203",
    )

    private class Env {
        val store = FakeColetorReportStore()
        val links = FakeColetorStore()
        val remote = FakeColetorRemote()
        val jobs = FakePrintJobRepository()
        val queue = ColetorReportQueue(store, links, remote, jobs).apply { now = { 1_760_961_600_000L } }
    }

    private fun job(id: String, status: PrintJobStatus, error: String? = null) = PrintJob(
        id = id, printerId = "p1", type = PrintPayloadType.TSPL, payload = "x", documentName = "d", copies = 1, status = status,
        attempts = 1, maxAttempts = 3, errorMessage = error, externalDocumentId = null, originalJobId = null,
        createdAt = 0, startedAt = null, sentAt = null, updatedAt = 0,
    )

    @Test
    fun `job enviado vira relatorio no formato do contrato e sai da fila quando o PC aceita`() = runTest {
        val e = Env()
        e.queue.record("job-1", "fixture-gondola", label, printer)
        val pending = e.store.all().single()
        assertNull(pending.status)
        assertEquals(0, e.queue.flush()) // pendente não vai

        e.queue.onEvent(PrintJobEvent.Completed("job-1", null, 10, 1))
        assertTrue(e.store.all().isEmpty())
        @Suppress("UNCHECKED_CAST")
        val sent = StrictJson.parse(e.remote.reports.single()) as Map<String, Any?>
        assertEquals(
            setOf("reportId", "templateId", "templateVersion", "packageSha256", "target", "printer", "operator", "status", "errorMessage", "versionCheck", "labels", "at"),
            sent.keys,
        )
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(sent["reportId"] as String))
        assertEquals("fixture-gondola", sent["templateId"])
        assertEquals(7L, sent["templateVersion"])
        assertEquals(mapOf("language" to "TSPL", "dpi" to 203L), sent["target"])
        assertEquals(mapOf("name" to "MB241 \"balcão\"", "model" to "TSC_MB241"), sent["printer"])
        assertEquals("SENT", sent["status"])
        assertEquals("OFFLINE_GRACE", sent["versionCheck"])
        assertEquals(listOf(mapOf("bytesSha256" to "b".repeat(64))), sent["labels"])
        assertEquals("2025-10-20T12:00:00Z", sent["at"])
    }

    @Test
    fun `sem PC o relatorio fica guardado e sai depois, uma vez so`() = runTest {
        val e = Env()
        e.remote.reportAnswer = ColetorRemote.ReportResult.Unreachable
        e.queue.record("job-1", "t", label, printer)
        e.queue.onEvent(PrintJobEvent.Failed("job-1", null, "impressora sem papel", 3))
        val kept = e.store.all().single()
        assertEquals(ColetorPrintReport.Status.FAILED, kept.status)
        assertEquals("impressora sem papel", kept.errorMessage)

        e.remote.reportAnswer = ColetorRemote.ReportResult.Accepted
        assertEquals(1, e.queue.flush())
        assertEquals(0, e.queue.flush())
        assertEquals(2, e.remote.reports.size)
        // Reenvio leva o MESMO reportId (o PC deduplica).
        assertEquals(StrictJson.parse(e.remote.reports[0]).let { (it as Map<*, *>)["reportId"] }, (StrictJson.parse(e.remote.reports[1]) as Map<*, *>)["reportId"])
    }

    @Test
    fun `evento perdido e recuperado pelo estado do job no banco`() = runTest {
        val e = Env()
        listOf("sent" to PrintJobStatus.SENT, "failed" to PrintJobStatus.FAILED, "gone" to PrintJobStatus.CANCELLED, "wait" to PrintJobStatus.PENDING)
            .forEach { (id, st) ->
                e.queue.record(id, "t", label, printer)
                e.jobs.insert(job(id, st, if (st == PrintJobStatus.FAILED) "sem papel" else null))
            }
        e.queue.record("purged", "t", label, printer) // job já sumiu do histórico
        e.queue.reconcile()
        val byJob = e.store.all().associateBy { it.jobId }
        assertEquals(setOf("sent", "failed", "wait"), byJob.keys)
        assertEquals(ColetorPrintReport.Status.SENT, byJob["sent"]!!.status)
        assertEquals("sem papel", byJob["failed"]!!.errorMessage)
        assertNull(byJob["wait"]!!.status)
    }

    @Test
    fun `400 descarta, 401 segura e sem ligacao nao manda nada`() = runTest {
        val e = Env()
        e.queue.record("job-1", "t", label, printer)
        e.remote.reportAnswer = ColetorRemote.ReportResult.Unauthorized
        e.queue.onEvent(PrintJobEvent.Completed("job-1", null, 1, 1))
        assertEquals(1, e.store.all().size)
        e.remote.reportAnswer = ColetorRemote.ReportResult.Rejected
        e.queue.flush()
        assertTrue(e.store.all().isEmpty())

        val noLink = Env().apply { links.linkValue = null }
        noLink.queue.record("j", "t", label, printer)
        noLink.queue.onEvent(PrintJobEvent.Completed("j", null, 1, 1))
        assertTrue(noLink.remote.reports.isEmpty())
        assertEquals(1, noLink.store.all().size)
    }

    @Test
    fun `armazenamento local ida e volta`() {
        val r = ColetorPrintReport(
            "123e4567-e89b-12d3-a456-426614174000", "j", "t", 1, "a".repeat(64), "ZPL", 300, "nome \"x\"\n", null,
            "CONFIRMED", "b".repeat(64), 5, ColetorPrintReport.Status.FAILED, "erro\\x", "2026-10-20T12:00:00Z",
        )
        assertEquals(r, ColetorPrintReport.fromStorageJson(r.toStorageJson()))
        assertNull(ColetorPrintReport.fromStorageJson("{}"))
    }
}
