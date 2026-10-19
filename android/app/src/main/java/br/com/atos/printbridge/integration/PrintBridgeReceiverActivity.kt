package br.com.atos.printbridge.integration

import br.com.atos.printbridge.domain.queue.JobSource
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.domain.error.PrintBridgeException
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Entry point de integração externa (Prompt 7). Não desenha UI própria (tema translúcido) —
 * apenas valida, enfileira e finaliza, devolvendo o resultado via setResult para o app chamador
 * quando ele foi iniciado com startActivityForResult.
 */
@AndroidEntryPoint
class PrintBridgeReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var enqueuePrintJobUseCase: EnqueuePrintJobUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val zplContent = intent.getStringExtra(PrintBridgeIntentContract.EXTRA_ZPL_CONTENT)
        val documentName = intent.getStringExtra(PrintBridgeIntentContract.EXTRA_DOCUMENT_NAME) ?: "documento-externo"
        val copies = intent.getIntExtra(PrintBridgeIntentContract.EXTRA_COPIES, 1)
        val printerId = intent.getStringExtra(PrintBridgeIntentContract.EXTRA_PRINTER_ID)
        val externalDocumentId = intent.getStringExtra(PrintBridgeIntentContract.EXTRA_EXTERNAL_DOCUMENT_ID)

        if (zplContent.isNullOrBlank()) {
            Logger.event(LogLevel.WARN, "external_submit_rejected", extra = mapOf("reason" to "empty_payload"))
            finishWithError("EXTRA_ZPL_CONTENT ausente ou vazio")
            return
        }

        lifecycleScope.launch {
            try {
                val job = enqueuePrintJobUseCase(
                    printerId = printerId,
                    type = PrintPayloadType.ZPL,
                    payload = zplContent,
                    documentName = documentName,
                    copies = copies,
                    externalDocumentId = externalDocumentId,
                    sourceApp = JobSource.externalApp(callingPackage ?: referrer?.host),
                )
                setResult(
                    Activity.RESULT_OK,
                    android.content.Intent().putExtra(PrintBridgeIntentContract.RESULT_EXTRA_JOB_ID, job.id),
                )
                finish()
            } catch (e: PrintBridgeException) {
                Logger.event(LogLevel.WARN, "external_submit_rejected", extra = mapOf("reason" to (e.message ?: "erro")))
                finishWithError(e.message ?: "Falha ao enfileirar impressão")
            }
        }
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(
            Activity.RESULT_CANCELED,
            android.content.Intent().putExtra(PrintBridgeIntentContract.RESULT_EXTRA_ERROR, message),
        )
        finish()
    }
}
