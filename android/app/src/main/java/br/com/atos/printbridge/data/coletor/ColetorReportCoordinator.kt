package br.com.atos.printbridge.data.coletor

import android.content.Context
import br.com.atos.printbridge.application.coletor.ColetorReportQueue
import br.com.atos.printbridge.application.coletor.ColetorReportStore
import br.com.atos.printbridge.domain.coletor.ColetorPrintReport
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Um arquivo por relatório em files/coletor/reports (armazenamento privado do app). */
@Singleton
class FileColetorReportStore @Inject constructor(@ApplicationContext private val context: Context) : ColetorReportStore {

    private val dir by lazy { File(context.filesDir, "coletor/reports").apply { mkdirs() } }
    private val safe = Regex("^[0-9a-f-]{36}$")

    override fun all(): List<ColetorPrintReport> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            ColetorPrintReport.fromStorageJson(f.readText(Charsets.UTF_8)) ?: run {
                // Ilegível: nunca vai sair daqui; apaga para não travar a fila.
                f.delete()
                null
            }
        }

    override fun save(report: ColetorPrintReport) {
        require(safe.matches(report.reportId)) { "reportId inválido" }
        val tmp = File(dir, "${report.reportId}.json.tmp")
        tmp.writeText(report.toStorageJson(), Charsets.UTF_8)
        val target = File(dir, "${report.reportId}.json")
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "não deu para gravar o relatório" }
        }
    }

    override fun delete(reportId: String) {
        if (safe.matches(reportId)) File(dir, "$reportId.json").delete()
    }
}

/**
 * Liga a fila de relatórios ao resto do app: ouve os eventos da fila de impressão e, ao abrir o
 * app, reconcilia e reenvia o que ficou pendente. Tudo em segundo plano (Dispatchers.IO).
 */
@Singleton
class ColetorReportCoordinator @Inject constructor(
    private val queue: ColetorReportQueue,
    private val events: PrintJobEventBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            events.flow.collect { e ->
                runCatching { queue.onEvent(e) }.onFailure { log("coletor_report_event_failed", it) }
            }
        }
        scope.launch {
            runCatching {
                queue.reconcile()
                queue.flush()
            }.onFailure { log("coletor_report_startup_failed", it) }
        }
    }

    private fun log(event: String, e: Throwable) =
        Logger.event(LogLevel.WARN, event, extra = mapOf("error" to (e.message ?: e::class.simpleName)))
}
