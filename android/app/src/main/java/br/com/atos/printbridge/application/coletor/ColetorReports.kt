package br.com.atos.printbridge.application.coletor

import br.com.atos.printbridge.domain.coletor.ColetorPrintReport
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Relatórios guardados no aparelho até o PC aceitar. Implementação: data/coletor/FileColetorReportStore. */
interface ColetorReportStore {
    fun all(): List<ColetorPrintReport>
    fun save(report: ColetorPrintReport)
    fun delete(reportId: String)
}

/**
 * Fila de relatórios de impressão dos templates do designer (contrato: POST /coletor/v1/print-reports,
 * best-effort, idempotente por reportId). Ciclo:
 *  1. [record] quando o job entra na fila (relatório pendente);
 *  2. [onEvent] quando a fila dá o job como enviado/falho; [reconcile] cobre o evento perdido
 *     (o barramento descarta sob pressão e o processo pode morrer) lendo o estado no banco;
 *  3. [flush] manda os prontos. 200 ou "já registrado" apaga; 400 (o PC nunca vai aceitar) apaga
 *     com log; rede/401/5xx: fica para a próxima. Nada disso segura ou atrasa a impressão.
 */
@Singleton
class ColetorReportQueue @Inject constructor(
    private val store: ColetorReportStore,
    private val links: ColetorLocalStore,
    private val remote: ColetorRemote,
    private val jobs: PrintJobRepository,
) {
    private val mutex = Mutex()
    var now: () -> Long = System::currentTimeMillis

    suspend fun record(jobId: String, templateId: String, label: DesignerLabel, printer: Printer) = mutex.withLock {
        val (language, dpi) = label.target.split('/').let { it[0] to it[1].toInt() }
        store.save(
            ColetorPrintReport(
                reportId = UUID.randomUUID().toString(),
                jobId = jobId,
                templateId = templateId,
                templateVersion = label.templateVersion,
                packageSha256 = label.packageSha256,
                language = language,
                dpi = dpi,
                printerName = printer.name.take(120),
                printerModel = printer.model.name,
                versionCheck = label.versionCheck.name,
                labelSha256 = label.labelSha256,
                createdAtMillis = now(),
            ),
        )
        trim()
    }

    suspend fun onEvent(event: PrintJobEvent) {
        val status = when (event) {
            is PrintJobEvent.Completed -> ColetorPrintReport.Status.SENT
            is PrintJobEvent.Failed -> ColetorPrintReport.Status.FAILED
            else -> return
        }
        val changed = mutex.withLock {
            val r = store.all().firstOrNull { it.jobId == event.jobId && !it.ready } ?: return@withLock false
            store.save(finish(r, status, (event as? PrintJobEvent.Failed)?.error))
            true
        }
        if (changed) flush()
    }

    /** Pendentes cujo job já terminou no banco (evento perdido, app reiniciado). */
    suspend fun reconcile() = mutex.withLock {
        for (r in store.all().filter { !it.ready }) {
            val job = jobs.getById(r.jobId)
            when (job?.status) {
                PrintJobStatus.SENT -> store.save(finish(r, ColetorPrintReport.Status.SENT, null))
                PrintJobStatus.FAILED -> store.save(finish(r, ColetorPrintReport.Status.FAILED, job.errorMessage))
                // Cancelado/removido antes de sair, ou job que sumiu (histórico purgado): nada foi à impressora.
                PrintJobStatus.CANCELLED, PrintJobStatus.DELETED_BY_OPERATOR, null -> store.delete(r.reportId)
                PrintJobStatus.PENDING, PrintJobStatus.PROCESSING -> Unit
            }
        }
    }

    suspend fun flush(): Int = mutex.withLock {
        val link = links.link() ?: return@withLock 0
        var sent = 0
        for (r in store.all().filter { it.ready }.sortedBy { it.createdAtMillis }) {
            when (remote.report(link, r.toContractJson())) {
                ColetorRemote.ReportResult.Accepted -> {
                    store.delete(r.reportId)
                    sent++
                }
                ColetorRemote.ReportResult.Rejected -> {
                    Logger.event(LogLevel.WARN, "coletor_report_rejected", extra = mapOf("reportId" to r.reportId, "templateId" to r.templateId))
                    store.delete(r.reportId)
                }
                // PC fora ou coletor sem acesso: tenta de novo depois; não insiste agora.
                ColetorRemote.ReportResult.Unauthorized, ColetorRemote.ReportResult.Unreachable -> break
            }
        }
        sent
    }

    private fun finish(r: ColetorPrintReport, status: ColetorPrintReport.Status, error: String?) =
        r.copy(status = status, errorMessage = error?.take(500), finishedAt = Instant.ofEpochMilli(now()).toString())

    /** Limite de guarda: sem PC por muito tempo, os mais antigos saem (é best-effort, não é auditoria primária). */
    private fun trim() {
        val all = store.all()
        if (all.size <= MAX_STORED) return
        all.sortedBy { it.createdAtMillis }.take(all.size - MAX_STORED).forEach {
            Logger.event(LogLevel.WARN, "coletor_report_dropped", extra = mapOf("reportId" to it.reportId))
            store.delete(it.reportId)
        }
    }

    companion object {
        const val MAX_STORED = 2000
    }
}
