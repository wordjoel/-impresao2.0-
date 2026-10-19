package br.com.atos.printbridge.service

import android.os.Bundle
import br.com.atos.printbridge.api.PrintBridgeContract as C
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.error.EmptyPayloadException
import br.com.atos.printbridge.domain.error.InvalidCopiesException
import br.com.atos.printbridge.domain.error.InvalidMediaProfileException
import br.com.atos.printbridge.domain.error.InvalidPortException
import br.com.atos.printbridge.domain.error.InvalidPrinterAddressException
import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PayloadTooLargeException
import br.com.atos.printbridge.domain.error.PrintBridgeException
import br.com.atos.printbridge.domain.error.PrintJobNotFoundException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.util.ErrorTranslator

/**
 * Tradução entre o domínio e o contrato em Bundle da API local. Funções puras (sem Service nem
 * Binder), para que o formato que os apps clientes recebem seja testável.
 */
object PrintBridgeResults {

    fun ok(fill: Bundle.() -> Unit = {}): Bundle = Bundle().apply {
        putBoolean(C.RESULT_OK, true)
        fill()
    }

    /**
     * Erro de negócio → código estável + mensagem legível. Para exceção inesperada a mensagem é a
     * genérica do ErrorTranslator — nunca stack trace/detalhe interno atravessando o Binder.
     */
    fun error(e: Throwable): Bundle = Bundle().apply {
        putBoolean(C.RESULT_OK, false)
        putString(C.RESULT_ERROR_CODE, codeOf(e))
        putString(
            C.RESULT_ERROR_MESSAGE,
            when (e) {
                // Para o desenvolvedor do app cliente o detalhe técnico do domínio é útil (qual
                // variável faltou, qual limite estourou) e não expõe nada interno.
                is PrintBridgeException -> e.message ?: ErrorTranslator.translate(e)
                is IllegalArgumentException -> e.message ?: "Pedido inválido"
                else -> ErrorTranslator.translate(e)
            },
        )
        if (e is MissingTemplateVariablesException) putStringArrayList("missingVariables", ArrayList(e.missing))
    }

    fun codeOf(e: Throwable): String = when (e) {
        is UnknownTemplateException -> C.ERROR_UNKNOWN_TEMPLATE
        is MissingTemplateVariablesException -> C.ERROR_MISSING_VARIABLES
        is InvalidTemplateValueException -> C.ERROR_INVALID_VARIABLE_VALUE
        is TemplateLanguageMismatchException -> C.ERROR_LANGUAGE_MISMATCH
        is DocumentRasterizationException -> C.ERROR_DOCUMENT_UNREADABLE
        is PayloadTooLargeException -> C.ERROR_PAYLOAD_TOO_LARGE
        is NoDefaultPrinterException, is PrinterNotFoundException -> C.ERROR_NO_PRINTER
        is InvalidMediaProfileException, is InvalidPrinterAddressException, is InvalidPortException -> C.ERROR_INVALID_PRINTER_PROFILE
        is LicenseRestrictedException -> C.ERROR_LICENSE_RESTRICTED
        is PrintJobNotFoundException -> C.ERROR_JOB_NOT_FOUND
        is EmptyPayloadException, is InvalidCopiesException, is IllegalArgumentException -> C.ERROR_INVALID_REQUEST
        else -> C.ERROR_INTERNAL
    }

    fun eventBundle(event: PrintJobEvent, externalDocumentId: String?): Bundle = Bundle().apply {
        putString(C.EVENT_JOB_ID, event.jobId)
        event.groupId?.let { putString(C.EVENT_GROUP_ID, it) }
        externalDocumentId?.let { putString(C.EVENT_EXTERNAL_ID, it) }
        when (event) {
            is PrintJobEvent.Completed -> {
                putString(C.EVENT_TYPE, C.EVENT_JOB_COMPLETED)
                putLong(C.EVENT_DURATION_MS, event.durationMillis)
                putInt(C.EVENT_ATTEMPT, event.attempts)
            }
            is PrintJobEvent.Failed -> {
                putString(C.EVENT_TYPE, C.EVENT_JOB_FAILED)
                putString(C.EVENT_ERROR, event.error)
                putInt(C.EVENT_ATTEMPT, event.attempts)
            }
            is PrintJobEvent.Retrying -> {
                putString(C.EVENT_TYPE, C.EVENT_JOB_RETRYING)
                putString(C.EVENT_ERROR, event.error)
                putInt(C.EVENT_ATTEMPT, event.attempt)
                putInt(C.EVENT_MAX_ATTEMPTS, event.maxAttempts)
                putLong(C.EVENT_NEXT_RETRY_MS, event.nextRetryInMillis)
            }
            is PrintJobEvent.PagePrinted -> {
                putString(C.EVENT_TYPE, C.EVENT_PAGE_PRINTED)
                putInt(C.EVENT_PAGE, event.pageNumber)
                putInt(C.EVENT_PAGE_COUNT, event.pageCount)
            }
            is PrintJobEvent.DocumentFinished -> {
                putString(C.EVENT_TYPE, C.EVENT_DOCUMENT_FINISHED)
                putInt(C.EVENT_PAGES_OK, event.pagesOk)
                putInt(C.EVENT_PAGE_COUNT, event.pagesTotal)
            }
        }
    }
}
