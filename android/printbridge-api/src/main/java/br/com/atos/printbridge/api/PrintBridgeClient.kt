package br.com.atos.printbridge.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Erro de negócio devolvido pelo Print Bridge; decida por [code] (PrintBridgeContract.ERROR_*). */
class PrintBridgeRemoteException(val code: String, message: String, val missingVariables: List<String> = emptyList()) :
    Exception("$code: $message")

/**
 * Cliente de referência da API local, para os apps ATHOS (e para o teste de integração).
 * Expõe a interface tipada do prompt mestre (printJob → jobId, getJobStatus → JobStatus...) sobre
 * o AIDL em Bundle. Todas as chamadas trocam para Dispatchers.IO — seguro chamar da main.
 *
 * Requisitos no app cliente:
 * - `<uses-permission android:name="br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB"/>` e
 *   assinatura com a mesma chave do Print Bridge (a permissão é `signature`);
 * - Android 11+: `<queries><package android:name="br.com.atos.printbridge"/></queries>`.
 *
 * Uso:
 * ```
 * val bridge = PrintBridgeClient.connect(context)
 * bridge.setListener { event -> /* event.getString(PrintBridgeContract.EVENT_TYPE) */ }
 * val jobId = bridge.printJob(PrintJobRequest(DocumentType.TEMPLATE, templateId = "sif-pesagem-100x72", variables = dados))
 * bridge.close()
 * ```
 */
class PrintBridgeClient private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val service: IPrintBridgeService,
) : AutoCloseable {

    private var callback: IPrintBridgeCallback.Stub? = null

    val apiVersion: Int get() = service.apiVersion

    /** @return id do job, ou do documento (PDF multipágina) — use em [getJobStatus]. */
    suspend fun printJob(request: PrintJobRequest): String = withContext(Dispatchers.IO) {
        unwrap(service.printJob(request.toBundle())).getString(PrintBridgeContract.RESULT_JOB_ID)!!
    }

    suspend fun getJobStatus(jobId: String): JobStatus = withContext(Dispatchers.IO) {
        JobStatus.fromBundle(unwrap(service.getJobStatus(jobId)))
    }

    /** @return id da impressora cadastrada/atualizada. */
    suspend fun registerPrinterProfile(profile: PrinterProfile): String = withContext(Dispatchers.IO) {
        unwrap(service.registerPrinterProfile(profile.toBundle())).getString(PrintBridgeContract.RESULT_PRINTER_ID)!!
    }

    suspend fun listPrinterProfiles(storeId: String = ""): List<PrinterProfile> = withContext(Dispatchers.IO) {
        service.listPrinterProfiles(storeId).map { PrinterProfile.fromBundle(it) }
    }

    /**
     * Recebe os eventos dos jobs enviados POR ESTE APP (job-completed, job-failed, job-retrying,
     * page-x-of-n-printed, document-finished). Chamado numa thread do Binder. Nulo remove.
     */
    fun setListener(listener: ((Bundle) -> Unit)?) {
        callback?.let { service.unregisterCallback(it) }
        callback = listener?.let { l ->
            object : IPrintBridgeCallback.Stub() {
                override fun onJobEvent(event: Bundle) = l(event)
            }.also { service.registerCallback(it) }
        }
    }

    override fun close() {
        runCatching { setListener(null) }
        context.unbindService(connection)
    }

    private fun unwrap(result: Bundle): Bundle {
        if (result.getBoolean(PrintBridgeContract.RESULT_OK)) return result
        throw PrintBridgeRemoteException(
            code = result.getString(PrintBridgeContract.RESULT_ERROR_CODE) ?: PrintBridgeContract.ERROR_INTERNAL,
            message = result.getString(PrintBridgeContract.RESULT_ERROR_MESSAGE).orEmpty(),
            missingVariables = result.getStringArrayList("missingVariables").orEmpty(),
        )
    }

    companion object {
        const val DEFAULT_PACKAGE = "br.com.atos.printbridge"
        const val DEBUG_PACKAGE = "$DEFAULT_PACKAGE.debug"

        /**
         * Conecta no Print Bridge de produção e, se não estiver instalado, no build debug
         * (homologação). @throws IllegalStateException nenhum dos dois disponível/permitido.
         */
        suspend fun connectAny(context: Context): PrintBridgeClient =
            try {
                connect(context, DEFAULT_PACKAGE)
            } catch (e: IllegalStateException) {
                try {
                    connect(context, DEBUG_PACKAGE)
                } catch (_: IllegalStateException) {
                    throw e
                }
            }

        /**
         * Faz o bind e suspende até conectar.
         * @param packageName pacote do Print Bridge instalado (build debug = "$DEFAULT_PACKAGE.debug").
         * @throws IllegalStateException Print Bridge não instalado ou sem permissão para o bind.
         */
        suspend fun connect(context: Context, packageName: String = DEFAULT_PACKAGE): PrintBridgeClient {
            val appContext = context.applicationContext
            return suspendCancellableCoroutine { cont ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                        if (cont.isActive) cont.resume(PrintBridgeClient(appContext, this, IPrintBridgeService.Stub.asInterface(binder)))
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                val intent = Intent(PrintBridgeContract.ACTION_BIND).setPackage(packageName)
                val bound = try {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    cont.resumeWithException(IllegalStateException("Sem permissão para usar o Print Bridge ($packageName)", e))
                    return@suspendCancellableCoroutine
                }
                if (!bound) {
                    appContext.unbindService(connection)
                    cont.resumeWithException(IllegalStateException("Print Bridge não encontrado ($packageName)"))
                }
                cont.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
            }
        }
    }
}
