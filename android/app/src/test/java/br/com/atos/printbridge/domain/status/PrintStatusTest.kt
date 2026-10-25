package br.com.atos.printbridge.domain.status

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.license.ManagedStatusPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build

/**
 * Regras do estado que vai ao MDM (item 2): derivação, limite de 1000 caracteres e quando publicar.
 * Robolectric: o org.json do android.jar da JVM pura é só esboço (devolve null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PrintStatusTest {
    private val now = 1_800_000_000_000L

    private fun status(
        name: String = "Balcao",
        enabled: Boolean = true,
        sent: Long? = null,
        failed: Long? = null,
        error: String? = null,
        active: Int = 0,
    ) = PrinterStatus(name, "10.0.0.20:9100", enabled, false, active, sent, failed, error)

    private fun snapshot(printers: List<PrinterStatus> = emptyList(), oldest: Long? = null, pending: Int = 0) =
        PrintStatusSnapshot(now, pending, 0, oldest, 0, 0, printers)

    @Test
    fun `estado - falha depois do ultimo envio OK e FAILING, envio depois da falha volta a OK`() {
        assertEquals(PrinterState.NO_ACTIVITY, status().state)
        assertEquals(PrinterState.OK, status(sent = 10).state)
        assertEquals(PrinterState.FAILING, status(failed = 10).state)
        assertEquals(PrinterState.FAILING, status(sent = 10, failed = 20).state)
        assertEquals(PrinterState.OK, status(sent = 30, failed = 20).state)
        assertEquals(PrinterState.DISABLED, status(enabled = false, failed = 20).state)
    }

    @Test
    fun `gravidade - impressora falhando ou fila parada ha 10 min e ERROR`() {
        assertEquals(PrintStatusSeverity.INFO, PrintStatusEncoder.severity(snapshot(listOf(status(sent = 1)))))
        assertEquals(PrintStatusSeverity.ERROR, PrintStatusEncoder.severity(snapshot(listOf(status(failed = 1)))))
        assertEquals(PrintStatusSeverity.INFO, PrintStatusEncoder.severity(snapshot(oldest = 599, pending = 1)))
        assertEquals(PrintStatusSeverity.ERROR, PrintStatusEncoder.severity(snapshot(oldest = 600, pending = 1)))
    }

    @Test
    fun `codificacao - JSON v1 legivel, erro cortado e espacos normalizados`() {
        val text = PrintStatusEncoder.encode(snapshot(listOf(status(failed = now, error = "Conexao\n recusada   " + "x".repeat(200)))))
        val o = JSONObject(text)
        assertEquals(1, o.getInt("v"))
        assertEquals(now / 1000, o.getLong("t"))
        val p = o.getJSONArray("p").getJSONObject(0)
        assertEquals("FAIL", p.getString("s"))
        assertEquals(now / 1000, p.getLong("lf"))
        assertTrue(p.isNull("ls"))
        assertTrue(p.getString("e").startsWith("Conexao recusada x"))
        assertEquals(60, p.getString("e").length)
    }

    @Test
    fun `codificacao - nunca passa de 1000 caracteres, falhando primeiro, more conta o resto`() {
        val many = (1..40).map { i -> status(name = "Impressora numero $i", sent = 5, failed = if (i == 37) 9 else null, error = "erro".repeat(20)) }
        val text = PrintStatusEncoder.encode(snapshot(many))
        assertTrue("tamanho ${text.length}", text.length <= PrintStatusEncoder.MAX_DATA_CHARS)
        val o = JSONObject(text)
        val list = o.getJSONArray("p")
        assertEquals("Impressora numero 37", list.getJSONObject(0).getString("n"))
        assertEquals(40, list.length() + o.getInt("more"))
        assertTrue(o.getInt("more") > 0)
    }

    @Test
    fun `fingerprint - ignora horario e idade exata da fila, nao ignora mudanca real`() {
        val a = snapshot(listOf(status(sent = 5)), oldest = 30, pending = 1)
        val b = a.copy(generatedAt = now + 60_000, oldestPendingAgeSeconds = 90)
        assertEquals(PrintStatusEncoder.fingerprint(a), PrintStatusEncoder.fingerprint(b))
        assertNotEquals(PrintStatusEncoder.fingerprint(a), PrintStatusEncoder.fingerprint(a.copy(oldestPendingAgeSeconds = 700)))
        assertNotEquals(PrintStatusEncoder.fingerprint(a), PrintStatusEncoder.fingerprint(a.copy(pending = 2)))
    }

    @Test
    fun `publicacao - so quando muda ou a cada 15 minutos`() {
        assertTrue(ManagedStatusPolicy.shouldPublish("a", null, 0, now))
        assertFalse(ManagedStatusPolicy.shouldPublish("a", "a", now - 60_000, now))
        assertTrue(ManagedStatusPolicy.shouldPublish("a", "a", now - ManagedStatusPolicy.REFRESH_MILLIS, now))
        assertTrue(ManagedStatusPolicy.shouldPublish("b", "a", now - 1_000, now))
        assertEquals("Fila: 3 · impressoras com falha: 1", ManagedStatusPolicy.message(3, 1))
        assertEquals("Fila: 0", ManagedStatusPolicy.message(0, 0))
    }

    @Test
    fun `montagem - junta cadastro, atividade e o ultimo erro de cada impressora`() = runTest {
        fun printer(id: String, name: String) = Printer(
            id = id, storeId = null, name = name, model = PrinterModel.TSC_MB241, ipAddress = "10.0.0.$id", port = 9100,
            protocol = PrinterProtocol.TCP, language = PrinterLanguage.TSPL, isDefault = id == "1", enabled = true,
            lastSeenAt = null, createdAt = 0, updatedAt = 0,
        )
        val source = object : PrintStatusSource {
            override suspend fun printers() = listOf(printer("1", "Balcao"), printer("2", "Deposito"))
            override suspend fun queueSummary(since: Long): QueueSummaryRow {
                assertEquals(now - PrintStatusBuilder.WINDOW_MILLIS, since)
                return QueueSummaryRow(pending = 2, processing = 1, oldestPendingAt = now - 125_000, failedSince = 3, sentSince = 40)
            }
            override suspend fun printerActivity() = listOf(PrinterActivityRow("1", 3, lastSentAt = now - 10_000, lastFailedAt = now - 5_000))
            override suspend fun recentFailures(since: Long) = listOf(
                JobFailureRow("1", "timeout", now - 5_000),
                JobFailureRow("1", "antigo", now - 50_000),
            )
            override fun changes(): Flow<Long?> = emptyFlow()
        }
        val s = PrintStatusBuilder.build(source, now)
        assertEquals(125L, s.oldestPendingAgeSeconds)
        assertEquals(40, s.sentLast24h)
        val balcao = s.printers.first { it.name == "Balcao" }
        assertEquals(PrinterState.FAILING, balcao.state)
        assertEquals("timeout", balcao.lastError)
        assertEquals("10.0.0.1:9100", balcao.address)
        assertEquals(3, balcao.activeJobs)
        assertEquals(PrinterState.NO_ACTIVITY, s.printers.first { it.name == "Deposito" }.state)
    }
}
