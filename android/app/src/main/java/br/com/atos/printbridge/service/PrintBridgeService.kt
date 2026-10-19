package br.com.atos.printbridge.service

import br.com.atos.printbridge.domain.queue.JobSource
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import br.com.atos.printbridge.api.IPrintBridgeCallback
import br.com.atos.printbridge.api.IPrintBridgeService
import br.com.atos.printbridge.api.PrintBridgeContract
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.application.bridge.GetJobStatusUseCase
import br.com.atos.printbridge.application.bridge.PrinterProfileUseCases
import br.com.atos.printbridge.application.bridge.SubmitPrintRequestUseCase
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * API local do ATHOS Print Bridge (Fase 1, Etapa 6): Bound Service que expõe
 * [IPrintBridgeService] para os apps ATHOS instalados no mesmo coletor. App comum — sem privilégio
 * de sistema —, funciona igual em Android puro e sob MDM/EMM.
 *
 * Segurança: o bind exige a permissão `signature` SUBMIT_PRINT_JOB (manifesto), então só apps
 * assinados com a mesma chave ATHOS chegam aqui. Eventos de um job só são entregues ao app (uid)
 * que o enviou.
 *
 * As chamadas chegam em threads do pool do Binder; o trabalho suspenso roda em runBlocking nessa
 * thread (nunca na main), que é o modelo síncrono que o contrato AIDL documenta.
 */
@AndroidEntryPoint
class PrintBridgeService : Service() {

    @Inject lateinit var submitPrintRequestUseCase: SubmitPrintRequestUseCase
    @Inject lateinit var getJobStatusUseCase: GetJobStatusUseCase
    @Inject lateinit var printerProfileUseCases: PrinterProfileUseCases
    @Inject lateinit var eventBus: PrintJobEventBus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks = RemoteCallbackList<IPrintBridgeCallback>()

    /** Dono (uid) e id externo de cada job/documento enviado por esta API. Só em memória. */
    private data class Owner(val uid: Int, val externalDocumentId: String?)
    private val owners = ConcurrentHashMap<String, Owner>()

    override fun onCreate() {
        super.onCreate()
        scope.launch { eventBus.flow.collect(::deliver) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        callbacks.kill()
        super.onDestroy()
    }

    private val binder = object : IPrintBridgeService.Stub() {

        override fun getApiVersion(): Int = PrintBridgeContract.API_VERSION

        override fun printJob(request: Bundle?): Bundle = call("printJob") {
            val uid = Binder.getCallingUid()
            val parsed = PrintJobRequest.fromBundle(requireNotNull(request) { "request ausente" })
            val withBytes = parsed.documentFd?.let { fd -> parsed.copy(documentBytes = readDocument(fd), documentFd = null) } ?: parsed
            val source = JobSource.externalApp(packageManager.getNameForUid(uid))
            val submitted = runBlocking(Dispatchers.IO) { submitPrintRequestUseCase(withBytes, source) }
            val owner = Owner(uid, parsed.externalDocumentId)
            owners[submitted.id] = owner
            submitted.jobIds.forEach { owners[it] = owner }
            Logger.event(
                LogLevel.INFO,
                "api_print_job",
                jobId = submitted.id,
                printerId = submitted.printerId,
                extra = mapOf("type" to parsed.documentType.name, "pages" to submitted.pageCount, "uid" to uid),
            )
            PrintBridgeResults.ok {
                putString(PrintBridgeContract.RESULT_JOB_ID, submitted.id)
                putInt(PrintBridgeContract.RESULT_PAGE_COUNT, submitted.pageCount)
                putString(PrintBridgeContract.RESULT_PRINTER_ID, submitted.printerId)
            }
        }

        override fun getJobStatus(jobId: String?): Bundle = call("getJobStatus") {
            val status = runBlocking(Dispatchers.IO) { getJobStatusUseCase(requireNotNull(jobId) { "jobId ausente" }) }
            PrintBridgeResults.ok { putAll(status.toBundle()) }
        }

        override fun registerPrinterProfile(profile: Bundle?): Bundle = call("registerPrinterProfile") {
            val saved = runBlocking(Dispatchers.IO) {
                printerProfileUseCases.register(PrinterProfile.fromBundle(requireNotNull(profile) { "profile ausente" }))
            }
            Logger.event(LogLevel.INFO, "api_printer_registered", printerId = saved.id, extra = mapOf("uid" to Binder.getCallingUid()))
            PrintBridgeResults.ok { putString(PrintBridgeContract.RESULT_PRINTER_ID, saved.id) }
        }

        override fun listPrinterProfiles(storeId: String?): List<Bundle> =
            runBlocking(Dispatchers.IO) { printerProfileUseCases.list(storeId) }.map { it.toBundle() }

        override fun registerCallback(callback: IPrintBridgeCallback?) {
            callback?.let { callbacks.register(it, Binder.getCallingUid()) }
        }

        override fun unregisterCallback(callback: IPrintBridgeCallback?) {
            callback?.let { callbacks.unregister(it) }
        }
    }

    /** Nenhuma exceção de negócio atravessa o Binder: tudo vira Bundle ok=false + código. */
    private inline fun call(operation: String, block: () -> Bundle): Bundle =
        try {
            block()
        } catch (e: Exception) {
            Logger.event(LogLevel.WARN, "api_call_failed", extra = mapOf("operation" to operation, "code" to PrintBridgeResults.codeOf(e)))
            PrintBridgeResults.error(e)
        }

    private fun readDocument(fd: ParcelFileDescriptor): ByteArray =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                // Lê no máximo limite+1 bytes: um arquivo gigante nunca é carregado inteiro na memória.
                val bytes = input.readNBytesCompat(DocumentRasterizer.MAX_DOCUMENT_BYTES + 1)
                if (bytes.size > DocumentRasterizer.MAX_DOCUMENT_BYTES) {
                    throw DocumentRasterizationException("documento excede ${DocumentRasterizer.MAX_DOCUMENT_BYTES} bytes")
                }
                bytes
            }
        } catch (e: IOException) {
            throw DocumentRasterizationException("não foi possível ler o arquivo recebido", e)
        }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun deliver(event: PrintJobEvent) {
        val owner = owners[event.jobId] ?: event.groupId?.let { owners[it] } ?: return
        val bundle = PrintBridgeResults.eventBundle(event, owner.externalDocumentId)
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    if (callbacks.getBroadcastCookie(i) != owner.uid) continue
                    try {
                        callbacks.getBroadcastItem(i).onJobEvent(bundle)
                    } catch (_: android.os.RemoteException) {
                        // Cliente morreu: o RemoteCallbackList remove sozinho no próximo broadcast.
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }
}
