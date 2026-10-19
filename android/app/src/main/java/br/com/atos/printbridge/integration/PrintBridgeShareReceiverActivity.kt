package br.com.atos.printbridge.integration

import br.com.atos.printbridge.domain.queue.JobSource
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.application.bridge.SubmitPrintRequestUseCase
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.domain.error.PrintBridgeException
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.util.ErrorTranslator
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recebe `ACTION_SEND` e `ACTION_SEND_MULTIPLE` de qualquer app Android ("Compartilhar").
 *
 * Aceita:
 *  - texto compartilhado (`EXTRA_TEXT`) → tratado como conteúdo ZPL;
 *  - arquivos (`EXTRA_STREAM`) → lidos via ContentResolver, com detecção de tipo:
 *      * mime de texto, `application/octet-stream`, mime contendo `zpl`, ou conteúdo que abre com
 *        cabeçalho ZPL (`` `^XA` ``) ou é ASCII puro → enfileirado como ZPL (busca o mesmo caminho
 *        dos jobs internos);
 *      * foto (`image/…`) ou `application/pdf` → rasterizado no papel/dpi da impressora padrão e
 *        posto na fila pelo [SubmitPrintRequestUseCase] (mesmo caminho da API e do "Imprimir");
 *      * vídeo/áudio ou tipo desconhecido sem cara de texto → rejeitado com mensagem ao operador.
 *    A decisão fica em [SharedContentClassifier].
 *
 * Segurança: leitura **limitada** a `MAX_PAYLOAD_SIZE_BYTES + 1` (nunca baixa um arquivo gigante
 * em memória só para rejeitá-lo); nenhum log com conteúdo do payload — apenas nome/tamanho/tipo.
 * A permissão de leitura dos URIs compartilhados é concedida automaticamente pelo sistema ao
 * chamar esta activity via share sheet; nenhuma permissão declarada no Manifest é necessária.
 */
@AndroidEntryPoint
class PrintBridgeShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var enqueuePrintJobUseCase: EnqueuePrintJobUseCase

    @Inject
    lateinit var submitPrintRequestUseCase: SubmitPrintRequestUseCase

    private var expected = 1
    private var completed = 0
    private val results = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentIntent = intent ?: run {
            finish()
            return
        }

        if (currentIntent.hasExtra(Intent.EXTRA_STREAM)) {
            // Converter foto/PDF leva alguns segundos e esta activity é transparente: sem este aviso o
            // operador acha que não foi e compartilha de novo (CF550, 29/09/2026: 2 etiquetas).
            Toast.makeText(this, "Recebido pelo A.print.Bridge, preparando a impressão…", Toast.LENGTH_SHORT).show()
        }

        when (currentIntent.action) {
            Intent.ACTION_SEND -> handleSend(currentIntent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(currentIntent)
            else -> {
                if (results.isEmpty()) {
                    Toast.makeText(this, "Ação de compartilhamento não suportada", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun handleSend(intent: Intent) {
        val streamUri = intent.streamUri()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        when {
            streamUri != null -> {
                expected = 1
                lifecycleScope.launch { enqueueStream(streamUri) }
            }
            !text.isNullOrBlank() -> {
                expected = 1
                lifecycleScope.launch { enqueueZpl(text) }
            }
            else -> {
                Logger.event(LogLevel.WARN, "share_submit_rejected", extra = mapOf("reason" to "empty_intent"))
                finishWith("Conteúdo compartilhado vazio ou inválido")
            }
        }
    }

    private fun handleSendMultiple(intent: Intent) {
        val uris = intent.streamUris()
        if (uris.isEmpty()) {
            handleSend(intent)
            return
        }
        val capped = uris.take(MAX_FILES_PER_SHARE)
        expected = capped.size
        lifecycleScope.launch {
            capped.forEach { enqueueStream(it) }
        }
    }

    private suspend fun enqueueZpl(text: String) {
        val documentName = "Compartilhado-${System.currentTimeMillis()}"
        try {
            val job = enqueuePrintJobUseCase(
                printerId = null,
                type = PrintPayloadType.ZPL,
                payload = text,
                documentName = documentName,
                copies = 1,
                externalDocumentId = null,
                sourceApp = JobSource.SHARE,
            )
            Logger.event(
                LogLevel.INFO,
                "share_submit_enqueued",
                jobId = job.id,
                extra = mapOf("source" to "share", "kind" to "text", "documentName" to documentName),
            )
            finishWith("Enviado para a fila: ${job.documentName}")
        } catch (e: PrintBridgeException) {
            Logger.event(LogLevel.WARN, "share_submit_rejected", extra = mapOf("reason" to (e.message ?: "erro")))
            finishWith(ErrorTranslator.translate(e))
        }
    }

    private suspend fun enqueueStream(uri: Uri) {
        val displayName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
        val mime = contentResolver.getType(uri)

        // Pelo nome: a galeria pode gerar um URI novo a cada compartilhamento da mesma foto.
        if (!RecentShares.claim(displayName ?: uri.toString(), System.currentTimeMillis())) {
            Logger.event(LogLevel.INFO, "share_submit_duplicate_ignored", extra = mapOf("name" to (displayName ?: "arquivo")))
            record("Já enviado há instantes, não repeti: ${displayName ?: "arquivo"}")
            return
        }

        // Foto/PDF podem ter até MAX_DOCUMENT_BYTES; texto/ZPL é conferido abaixo contra o limite da fila.
        val (bytes, truncated) = try {
            withContext(Dispatchers.IO) { readBounded(uri, DocumentRasterizer.MAX_DOCUMENT_BYTES + 1) }
        } catch (e: SecurityException) {
            Logger.event(LogLevel.WARN, "share_submit_read_denied", extra = mapOf("name" to (displayName ?: uri.toString())))
            record("Sem permissão para ler o arquivo compartilhado: ${displayName ?: "arquivo"}")
            return
        } catch (e: IOException) {
            Logger.event(LogLevel.WARN, "share_submit_read_failed", extra = mapOf("name" to (displayName ?: uri.toString())))
            record("Falha ao ler o arquivo compartilhado: ${displayName ?: "arquivo"}")
            return
        }

        val content = if (bytes.isEmpty()) null else SharedContentClassifier.classify(mime, bytes)
        when {
            content == null -> {
                Logger.event(LogLevel.WARN, "share_submit_empty_file", extra = mapOf("name" to (displayName ?: uri.toString())))
                record("Arquivo vazio ou ilegível: ${displayName ?: "arquivo"}")
            }
            content is SharedContent.Document && truncated -> {
                Logger.event(LogLevel.WARN, "share_submit_too_large", extra = mapOf("name" to (displayName ?: uri.toString()), "bytes" to bytes.size))
                record("Arquivo maior que ${DocumentRasterizer.MAX_DOCUMENT_BYTES / (1024 * 1024)} MB não é aceito: ${displayName ?: "arquivo"}")
            }
            content is SharedContent.Document -> submitDocument(content.type, bytes, displayName, mime)
            content == SharedContent.Unsupported -> {
                Logger.event(LogLevel.WARN, "share_submit_unsupported_type", extra = mapOf("mime" to (mime ?: ""), "name" to (displayName ?: uri.toString())))
                record("Tipo não suportado (${mime ?: "desconhecido"}). Envie foto, PDF, texto ou ZPL: ${displayName ?: "arquivo"}")
            }
            content == SharedContent.Zpl && (truncated || bytes.size > PrintJob.MAX_PAYLOAD_SIZE_BYTES) -> {
                Logger.event(LogLevel.WARN, "share_submit_too_large", extra = mapOf("name" to (displayName ?: uri.toString()), "bytes" to bytes.size))
                record("Arquivo maior que ${PrintJob.MAX_PAYLOAD_SIZE_BYTES / 1024} KB não é aceito: ${displayName ?: "arquivo"}")
            }
            content == SharedContent.Zpl -> {
                val documentName = displayName ?: "Compartilhado-${System.currentTimeMillis()}"
                try {
                    val job = enqueuePrintJobUseCase(
                        printerId = null,
                        type = PrintPayloadType.ZPL,
                        payload = String(bytes, Charsets.UTF_8),
                        documentName = documentName,
                        copies = 1,
                        externalDocumentId = null,
                        sourceApp = JobSource.SHARE,
                    )
                    Logger.event(
                        LogLevel.INFO,
                        "share_submit_enqueued",
                        jobId = job.id,
                        extra = mapOf(
                            "source" to "share",
                            "kind" to "file",
                            "documentName" to documentName,
                            "mime" to (mime ?: ""),
                            "bytes" to bytes.size,
                        ),
                    )
                    record("Enviado para a fila: $documentName")
                } catch (e: PrintBridgeException) {
                    Logger.event(LogLevel.WARN, "share_submit_rejected", extra = mapOf("reason" to (e.message ?: "erro")))
                    record(ErrorTranslator.translate(e))
                }
            }
            else -> {
                Logger.event(LogLevel.WARN, "share_submit_unknown_type", extra = mapOf("mime" to (mime ?: ""), "name" to (displayName ?: uri.toString())))
                record("Conteúdo não reconhecido como ZPL: ${displayName ?: "arquivo"}")
            }
        }
    }

    /**
     * Foto/PDF → [SubmitPrintRequestUseCase], o mesmo da API local e do "Imprimir" do sistema:
     * licença, rasterização no papel/dpi da impressora padrão e persistência na fila antes do envio.
     */
    private suspend fun submitDocument(type: DocumentType, bytes: ByteArray, displayName: String?, mime: String?) {
        val documentName = displayName ?: "Compartilhado-${System.currentTimeMillis()}"
        try {
            // Rasterizar foto/PDF é CPU pesada: fora da main thread.
            val submitted = withContext(Dispatchers.Default) {
                submitPrintRequestUseCase(PrintJobRequest(documentType = type, documentBytes = bytes, documentName = documentName), JobSource.SHARE)
            }
            Logger.event(
                LogLevel.INFO,
                "share_submit_enqueued",
                jobId = submitted.id,
                printerId = submitted.printerId,
                extra = mapOf(
                    "source" to "share",
                    "kind" to type.name.lowercase(),
                    "documentName" to documentName,
                    "mime" to (mime ?: ""),
                    "bytes" to bytes.size,
                    "pages" to submitted.jobIds.size,
                ),
            )
            val pages = submitted.jobIds.size
            record("Na fila do A.print.Bridge: $documentName" + if (pages > 1) " ($pages páginas)" else "")
        } catch (e: PrintBridgeException) {
            Logger.event(LogLevel.WARN, "share_submit_rejected", extra = mapOf("reason" to (e.message ?: "erro"), "kind" to type.name.lowercase()))
            record(ErrorTranslator.translate(e))
        }
    }

    /**
     * Lê no máximo [limit] bytes do URI. Devolve `truncated = true` se o arquivo for maior —
     * neste caso os bytes capturados param no limite, sem nunca ler o arquivo inteiro em memória.
     */
    private fun readBounded(uri: Uri, limit: Int): Pair<ByteArray, Boolean> {
        val input: InputStream = contentResolver.openInputStream(uri)
            ?: return ByteArray(0) to false
        return input.use { stream ->
            val buffer = ByteArrayOutputStream(1024)
            val chunk = ByteArray(8192)
            var total = 0
            var truncated = false
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (total + read > limit) {
                    val allowed = limit - total
                    if (allowed > 0) buffer.write(chunk, 0, allowed)
                    truncated = true
                    break
                }
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray() to truncated
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun record(message: String) {
        completed++
        results += message
        if (completed >= expected) {
            // Antes o resultado de arquivos era gravado mas nunca mostrado: o operador não sabia se entrou na fila.
            Toast.makeText(this, results.joinToString("\n"), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishWith(message: String) {
        results += message
        if (results.isNotEmpty()) {
            Toast.makeText(this, results.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }

    companion object {
        /** Limite de arquivos por compartilhamento múltiplo — proteção contra Intent gigante. */
        const val MAX_FILES_PER_SHARE = 20
    }
}