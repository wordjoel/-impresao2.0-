package br.com.atos.printbridge.printservice

import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import br.com.atos.printbridge.application.bridge.SubmittedPrint
import br.com.atos.printbridge.application.printservice.SystemJobOutcome
import br.com.atos.printbridge.application.printservice.SystemPrintJobBridge
import br.com.atos.printbridge.application.printservice.SystemPrinterSpec
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.util.ErrorTranslator
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * Fase 2: o Print Bridge como serviço de impressão do sistema (android.printservice). Qualquer app
 * com "Imprimir" (navegador, leitor de PDF, planilha) passa a ver as impressoras cadastradas no
 * Print Bridge, sem saber nada da API ATHOS.
 *
 * Diferente da API local (AIDL, protegida por `signature`), esta porta é aberta a qualquer app por
 * definição — quem controla é o Android: o serviço só funciona depois de ativado em
 * Configurações > Impressão (ou pelo MDM). A licença continua valendo: o PDF entra pelo mesmo
 * SubmitPrintRequestUseCase, que recusa com a licença fora de operação.
 *
 * Toda a lógica fica em [SystemPrintJobBridge]; aqui só a tradução para as classes do framework.
 * Callbacks do PrintService e os métodos de [PrintJob] exigem a main thread — por isso o escopo é
 * Main e o trabalho de disco/rede vai para IO.
 */
@AndroidEntryPoint
class AthosPrintService : PrintService() {

    @Inject lateinit var bridge: SystemPrintJobBridge
    @Inject lateinit var connectionTester: PrinterConnectionTester

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = HashMap<PrintJobId, Job>()
    private val submissions = HashMap<PrintJobId, SubmittedPrint>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    override fun onPrintJobQueued(printJob: PrintJob) {
        val localId = printJob.info.printerId?.localId
        val data = printJob.document.data
        if (localId == null || data == null) {
            printJob.fail("Documento ou impressora ausente no pedido de impressão")
            return
        }
        printJob.start()
        val id = printJob.id
        // PrintJob.getInfo()/getDocument().getInfo() só podem ser lidos na main thread (o Android lança
        // IllegalAccessError e derruba o app — CF550, 29/09/2026): captura aqui, antes do Dispatchers.IO.
        val copies = printJob.info.copies
        val documentName = printJob.document.info.name
        running[id] = scope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { readDocument(data) }
                val submitted = withContext(Dispatchers.IO) {
                    bridge.submit(localId, pdf, copies, documentName)
                }
                submissions[id] = submitted
                Logger.event(
                    LogLevel.INFO,
                    "system_print_job_queued",
                    jobId = submitted.id,
                    printerId = submitted.printerId,
                    extra = mapOf("pages" to submitted.pageCount),
                )
                val outcome = withContext(Dispatchers.IO) {
                    bridge.awaitOutcome(submitted) { reason -> scope.launch { if (printJob.isStarted) printJob.block(reason) } }
                }
                finish(printJob, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.event(LogLevel.WARN, "system_print_job_rejected", extra = mapOf("error" to e.javaClass.simpleName))
                if (!printJob.isCancelled) printJob.fail(ErrorTranslator.translate(e))
            } finally {
                running.remove(id)
                submissions.remove(id)
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val id = printJob.id
        scope.launch {
            submissions[id]?.let { withContext(Dispatchers.IO) { bridge.cancel(it) } }
            running.remove(id)?.cancel()
            printJob.cancel()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(printJob: PrintJob, outcome: SystemJobOutcome) {
        when (outcome) {
            SystemJobOutcome.Completed -> {
                // complete() só vale a partir de "iniciado"; um job bloqueado volta a iniciado antes.
                if (printJob.isBlocked) printJob.start()
                printJob.complete()
            }
            is SystemJobOutcome.Failed -> printJob.fail(outcome.reason)
            SystemJobOutcome.Cancelled -> printJob.cancel()
        }
    }

    /** Mesmo teto da API local: um PDF gigante nunca é lido inteiro para a memória. */
    private fun readDocument(fd: ParcelFileDescriptor): ByteArray =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val limit = DocumentRasterizer.MAX_DOCUMENT_BYTES
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() <= limit) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                if (out.size() > limit) throw DocumentRasterizationException("documento excede $limit bytes")
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw DocumentRasterizationException("não foi possível ler o documento do sistema", e)
        }

    private fun printerInfo(spec: SystemPrinterSpec, reachable: Boolean? = null): PrinterInfo {
        val id = generatePrinterId(spec.localId)
        val builder = PrinterCapabilitiesInfo.Builder(id)
        spec.medias.forEach { m ->
            builder.addMediaSize(PrintAttributes.MediaSize(m.id, m.label, m.widthMils, m.heightMils), m.id == spec.defaultMediaId)
        }
        spec.resolutionsDpi.forEach { dpi ->
            builder.addResolution(PrintAttributes.Resolution("PB_${dpi}DPI", "$dpi dpi", dpi, dpi), dpi == spec.defaultDpi)
        }
        val colorMode = if (spec.color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME
        val capabilities = builder
            .setColorModes(colorMode, colorMode)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        // Sempre IDLE, mesmo sem resposta: a fila guarda o job e reenvia quando a impressora voltar
        // (critério 4 da Fase 1). UNAVAILABLE travaria o botão "Imprimir" do diálogo.
        val description = if (reachable == false) "Sem resposta agora — imprime quando voltar · ${spec.description}" else spec.description
        return PrinterInfo.Builder(id, spec.name, PrinterInfo.STATUS_IDLE)
            .setDescription(description)
            .setCapabilities(capabilities)
            .build()
    }

    private inner class Session : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = publish()

        override fun onStopPrinterDiscovery() = Unit

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = publish()

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            scope.launch {
                val spec = withContext(Dispatchers.IO) { bridge.printer(printerId.localId) }
                if (spec == null) {
                    if (!isDestroyed) removePrinters(listOf(printerId))
                    return@launch
                }
                val reachable = withContext(Dispatchers.IO) {
                    runCatching { connectionTester.testAddress(spec.ipAddress, spec.port, PROBE_TIMEOUT_MILLIS).reachable }
                        .getOrDefault(false)
                }
                if (!isDestroyed) addPrinters(listOf(printerInfo(spec, reachable)))
            }
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() = Unit

        /** Cadastro do app → lista do diálogo; impressoras removidas/desativadas saem da lista. */
        private fun publish() {
            scope.launch {
                val specs = withContext(Dispatchers.IO) { bridge.printers() }
                if (isDestroyed) return@launch
                val current = specs.map { generatePrinterId(it.localId) }.toSet()
                val gone = printers.map { it.id }.filterNot { it in current }
                if (gone.isNotEmpty()) removePrinters(gone)
                addPrinters(specs.map { printerInfo(it) })
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 1_500
    }
}
