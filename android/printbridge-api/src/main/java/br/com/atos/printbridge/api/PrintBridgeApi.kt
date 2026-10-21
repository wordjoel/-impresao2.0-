package br.com.atos.printbridge.api

import android.os.Bundle

/*
 * Contrato público do ATHOS Print Bridge (Fase 1) para apps ATHOS no mesmo coletor.
 *
 * Este pacote é AUTOSSUFICIENTE de propósito (só depende de android.os.Bundle): é o que os apps
 * clientes copiam/consomem, junto com os .aidl de src/main/aidl. Nada aqui importa código interno
 * do Print Bridge.
 *
 * O transporte pelo Binder é sempre Bundle (não Parcelable próprio): um cliente compilado contra
 * uma versão antiga do contrato continua funcionando quando chaves novas forem adicionadas — só
 * as ignora. Ver [PrintBridgeContract.API_VERSION].
 */

enum class DocumentType { TEMPLATE, PDF, IMAGE, RAW_ZPL_TSPL }

/** Como converter PDF/imagem em 1 bit. Nulo no pedido = THRESHOLD para PDF, FLOYD_STEINBERG para imagem. */
enum class DitherMode { THRESHOLD, FLOYD_STEINBERG }

data class PrintJobRequest(
    val documentType: DocumentType,
    /** Obrigatório se TEMPLATE. */
    val templateId: String? = null,
    /** Obrigatório se TEMPLATE. */
    val variables: Map<String, String>? = null,
    /**
     * Obrigatório se PDF/IMAGE. Atenção ao limite do Binder (~1 MB por transação, somado a tudo que
     * estiver em trânsito no processo): documentos maiores devem ir por [documentFd].
     */
    val documentBytes: ByteArray? = null,
    /** Alternativa a [documentBytes] para PDF/IMAGE grandes: descritor de arquivo legível. */
    val documentFd: android.os.ParcelFileDescriptor? = null,
    /** Obrigatório se RAW_ZPL_TSPL. */
    val rawPayload: String? = null,
    /** Nulo = impressora padrão. */
    val printerProfileId: String? = null,
    /** Nome amigável exibido na fila/notificação do operador. */
    val documentName: String? = null,
    /** Identificador do documento no sistema chamador (volta nos eventos). */
    val externalDocumentId: String? = null,
    val copies: Int = 1,
    val ditherMode: DitherMode? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(K_DOCUMENT_TYPE, documentType.name)
        templateId?.let { putString(K_TEMPLATE_ID, it) }
        variables?.let { vars -> putBundle(K_VARIABLES, Bundle().apply { vars.forEach { (k, v) -> putString(k, v) } }) }
        documentBytes?.let { putByteArray(K_DOCUMENT_BYTES, it) }
        documentFd?.let { putParcelable(K_DOCUMENT_FD, it) }
        rawPayload?.let { putString(K_RAW_PAYLOAD, it) }
        printerProfileId?.let { putString(K_PRINTER_ID, it) }
        documentName?.let { putString(K_DOCUMENT_NAME, it) }
        externalDocumentId?.let { putString(K_EXTERNAL_ID, it) }
        putInt(K_COPIES, copies)
        ditherMode?.let { putString(K_DITHER, it.name) }
    }

    // ByteArray quebra o equals/hashCode gerado; comparação de conteúdo não é necessária no contrato.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        private const val K_DOCUMENT_TYPE = "documentType"
        private const val K_TEMPLATE_ID = "templateId"
        private const val K_VARIABLES = "variables"
        private const val K_DOCUMENT_BYTES = "documentBytes"
        private const val K_DOCUMENT_FD = "documentFd"
        private const val K_RAW_PAYLOAD = "rawPayload"
        private const val K_PRINTER_ID = "printerProfileId"
        private const val K_DOCUMENT_NAME = "documentName"
        private const val K_EXTERNAL_ID = "externalDocumentId"
        private const val K_COPIES = "copies"
        private const val K_DITHER = "ditherMode"

        /** @throws IllegalArgumentException bundle sem tipo de documento ou com tipo desconhecido. */
        @Suppress("DEPRECATION") // getParcelable(key, Class) só existe a partir da API 33.
        fun fromBundle(bundle: Bundle): PrintJobRequest {
            val typeName = requireNotNull(bundle.getString(K_DOCUMENT_TYPE)) { "documentType ausente" }
            val type = runCatching { DocumentType.valueOf(typeName) }
                .getOrElse { throw IllegalArgumentException("documentType desconhecido: $typeName") }
            return PrintJobRequest(
                documentType = type,
                templateId = bundle.getString(K_TEMPLATE_ID),
                variables = bundle.getBundle(K_VARIABLES)?.let { vars ->
                    vars.keySet().associateWith { vars.getString(it).orEmpty() }
                },
                documentBytes = bundle.getByteArray(K_DOCUMENT_BYTES),
                documentFd = bundle.getParcelable(K_DOCUMENT_FD),
                rawPayload = bundle.getString(K_RAW_PAYLOAD),
                printerProfileId = bundle.getString(K_PRINTER_ID),
                documentName = bundle.getString(K_DOCUMENT_NAME),
                externalDocumentId = bundle.getString(K_EXTERNAL_ID),
                copies = bundle.getInt(K_COPIES, 1),
                ditherMode = bundle.getString(K_DITHER)?.let { name -> DitherMode.entries.firstOrNull { it.name == name } },
            )
        }
    }
}

data class PrinterProfile(
    /** Nulo ao registrar = impressora nova. */
    val id: String?,
    val storeId: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 9100,
    /** "ZPL" ou "TSPL". */
    val protocol: String,
    val dpi: Int = 203,
    /** "LARGURAxALTURA" em mm, ex.: "100x72". */
    val defaultPaperSize: String,
    val isDefault: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        id?.let { putString("id", it) }
        putString("storeId", storeId)
        putString("name", name)
        putString("ipAddress", ipAddress)
        putInt("port", port)
        putString("protocol", protocol)
        putInt("dpi", dpi)
        putString("defaultPaperSize", defaultPaperSize)
        putBoolean("isDefault", isDefault)
    }

    companion object {
        fun fromBundle(b: Bundle) = PrinterProfile(
            id = b.getString("id"),
            storeId = b.getString("storeId").orEmpty(),
            name = b.getString("name").orEmpty(),
            ipAddress = b.getString("ipAddress").orEmpty(),
            port = b.getInt("port", 9100),
            protocol = b.getString("protocol").orEmpty(),
            dpi = b.getInt("dpi", 203),
            defaultPaperSize = b.getString("defaultPaperSize").orEmpty(),
            isDefault = b.getBoolean("isDefault", false),
        )

        /** "100x72" → (100, 72); nulo se mal formado. */
        fun parsePaperSize(value: String): Pair<Int, Int>? {
            val parts = value.lowercase().split('x').map { it.trim() }
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return w to h
        }
    }
}

/**
 * Estado de um job — ou de um documento inteiro, quando consultado pelo id de grupo devolvido por
 * printJob() para PDF multipágina. "Completed" = bytes entregues à impressora (não há confirmação
 * física de volta nesta fase).
 */
sealed class JobStatus {
    /** [position] = quantos jobs estão à frente na fila da mesma impressora (0 = é o próximo). */
    data class Queued(val position: Int) : JobStatus()
    data class Sending(val attempt: Int) : JobStatus()
    data class Completed(val durationMs: Long) : JobStatus()
    data class Failed(val error: String, val attempts: Int) : JobStatus()
    data class PartiallyCompleted(val pagesOk: Int, val pagesTotal: Int) : JobStatus()
    /** Cancelado pelo operador na tela de fila (extensão ao contrato do prompt mestre). */
    data object Cancelled : JobStatus()

    fun toBundle(): Bundle = Bundle().apply {
        when (val s = this@JobStatus) {
            is Queued -> { putString(K_STATE, "QUEUED"); putInt("position", s.position) }
            is Sending -> { putString(K_STATE, "SENDING"); putInt("attempt", s.attempt) }
            is Completed -> { putString(K_STATE, "COMPLETED"); putLong("durationMs", s.durationMs) }
            is Failed -> { putString(K_STATE, "FAILED"); putString("error", s.error); putInt("attempts", s.attempts) }
            is PartiallyCompleted -> {
                putString(K_STATE, "PARTIALLY_COMPLETED"); putInt("pagesOk", s.pagesOk); putInt("pagesTotal", s.pagesTotal)
            }
            Cancelled -> putString(K_STATE, "CANCELLED")
        }
    }

    companion object {
        private const val K_STATE = "state"

        fun fromBundle(b: Bundle): JobStatus = when (b.getString(K_STATE)) {
            "QUEUED" -> Queued(b.getInt("position"))
            "SENDING" -> Sending(b.getInt("attempt"))
            "COMPLETED" -> Completed(b.getLong("durationMs"))
            "PARTIALLY_COMPLETED" -> PartiallyCompleted(b.getInt("pagesOk"), b.getInt("pagesTotal"))
            "CANCELLED" -> Cancelled
            else -> Failed(b.getString("error").orEmpty(), b.getInt("attempts"))
        }
    }
}

object PrintBridgeContract {
    /** Sobe quando o contrato ganha algo que o cliente precisa saber que existe. */
    const val API_VERSION = 1

    const val ACTION_BIND = "br.com.atos.printbridge.action.BIND_PRINT_BRIDGE"
    const val PERMISSION = "br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB"

    // ---- resultado de chamada (Bundle devolvido por printJob/getJobStatus/registerPrinterProfile)
    const val RESULT_OK = "ok"
    const val RESULT_JOB_ID = "jobId"
    const val RESULT_PAGE_COUNT = "pageCount"
    const val RESULT_PRINTER_ID = "printerId"
    const val RESULT_ERROR_CODE = "errorCode"
    const val RESULT_ERROR_MESSAGE = "errorMessage"

    // ---- códigos de erro estáveis (o cliente decide por código, nunca pelo texto)
    const val ERROR_INVALID_REQUEST = "INVALID_REQUEST"
    const val ERROR_UNKNOWN_TEMPLATE = "UNKNOWN_TEMPLATE"
    const val ERROR_MISSING_VARIABLES = "MISSING_VARIABLES"
    const val ERROR_INVALID_VARIABLE_VALUE = "INVALID_VARIABLE_VALUE"
    const val ERROR_LANGUAGE_MISMATCH = "LANGUAGE_MISMATCH"

    /**
     * Template do designer do PC (passo 6) sem versão confirmada: o PC não respondeu e já passou a
     * janela offline de 15 min, ou o coletor foi revogado. Nada foi impresso; tente de novo com rede.
     */
    const val ERROR_TEMPLATE_VERSION_UNCONFIRMED = "TEMPLATE_VERSION_UNCONFIRMED"
    const val ERROR_DOCUMENT_UNREADABLE = "DOCUMENT_UNREADABLE"
    const val ERROR_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val ERROR_NO_PRINTER = "NO_PRINTER"
    const val ERROR_INVALID_PRINTER_PROFILE = "INVALID_PRINTER_PROFILE"
    const val ERROR_LICENSE_RESTRICTED = "LICENSE_RESTRICTED"
    const val ERROR_JOB_NOT_FOUND = "JOB_NOT_FOUND"
    const val ERROR_INTERNAL = "INTERNAL"

    // ---- eventos (Bundle entregue em IPrintBridgeCallback.onJobEvent)
    const val EVENT_TYPE = "event"
    const val EVENT_JOB_ID = "jobId"
    const val EVENT_GROUP_ID = "groupId"
    const val EVENT_EXTERNAL_ID = "externalDocumentId"
    const val EVENT_ERROR = "error"
    const val EVENT_ATTEMPT = "attempt"
    const val EVENT_MAX_ATTEMPTS = "maxAttempts"
    const val EVENT_NEXT_RETRY_MS = "nextRetryInMs"
    const val EVENT_DURATION_MS = "durationMs"
    const val EVENT_PAGE = "page"
    const val EVENT_PAGE_COUNT = "pageCount"
    const val EVENT_PAGES_OK = "pagesOk"

    const val EVENT_JOB_COMPLETED = "job-completed"
    const val EVENT_JOB_FAILED = "job-failed"
    const val EVENT_JOB_RETRYING = "job-retrying"
    const val EVENT_PAGE_PRINTED = "page-x-of-n-printed"
    const val EVENT_DOCUMENT_FINISHED = "document-finished"
}
