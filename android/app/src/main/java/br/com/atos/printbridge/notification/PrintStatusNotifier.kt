package br.com.atos.printbridge.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.atos.printbridge.MainActivity
import br.com.atos.printbridge.R
import br.com.atos.printbridge.domain.queue.PrintJobEvent
import br.com.atos.printbridge.domain.queue.PrintJobEventBus
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class NotificationKind { SUCCESS, RETRYING, FAILURE, PROGRESS }

data class PrintStatusMessage(val kind: NotificationKind, val title: String, val text: String)

/**
 * O que o operador lê para cada evento — função pura, testável. Resolve a dor nº 1 do fluxo antigo
 * ("nenhuma confirmação aparece; o operador precisa olhar a impressora"): todo job termina numa
 * mensagem explícita de enviado ou de falha, com o motivo.
 *
 * Nulo = evento que não merece notificação própria (ex.: "Completed" de uma página de documento,
 * que já aparece como progresso do documento).
 */
object PrintStatusMessages {
    fun of(event: PrintJobEvent, documentName: String, queuedCount: Int): PrintStatusMessage? {
        val queue = if (queuedCount > 0) " · $queuedCount na fila" else ""
        return when (event) {
            is PrintJobEvent.Completed ->
                if (event.groupId != null) null
                else PrintStatusMessage(NotificationKind.SUCCESS, "Enviado à impressora", "$documentName$queue")
            is PrintJobEvent.Retrying -> PrintStatusMessage(
                NotificationKind.RETRYING,
                "Tentando novamente (${event.attempt}/${event.maxAttempts})",
                "$documentName: ${event.error}$queue",
            )
            is PrintJobEvent.Failed -> PrintStatusMessage(
                NotificationKind.FAILURE,
                "Falha na impressão",
                "$documentName: ${event.error} (${event.attempts} tentativas). Toque para ver a fila.",
            )
            is PrintJobEvent.PagePrinted -> PrintStatusMessage(
                NotificationKind.PROGRESS,
                "Imprimindo documento",
                "Página ${event.pageNumber} de ${event.pageCount} enviada$queue",
            )
            is PrintJobEvent.DocumentFinished ->
                if (event.pagesOk == event.pagesTotal) {
                    PrintStatusMessage(NotificationKind.SUCCESS, "Documento enviado à impressora", "${event.pagesTotal} páginas$queue")
                } else {
                    PrintStatusMessage(
                        NotificationKind.FAILURE,
                        "Documento impresso pela metade",
                        "${event.pagesOk} de ${event.pagesTotal} páginas enviadas. Toque para ver a fila.",
                    )
                }
        }
    }

    /** Nome do documento sem o sufixo "(2/3)" que as páginas carregam na fila. */
    fun baseName(documentName: String): String = documentName.replace(Regex("""\s\(\d+/\d+\)$"""), "")
}

/**
 * Confirmação visual mínima (Fase 1, Etapa 7) em notificação do sistema: aparece para qualquer
 * origem de impressão (API local, Intent, compartilhar, tela do app) sem o operador abrir nada.
 * Uma notificação por job/documento, atualizada no lugar (retry → enviado/falha).
 * Sucesso some sozinho; falha fica até o operador ver.
 */
@Singleton
class PrintStatusNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: PrintJobEventBus,
    private val printJobRepository: PrintJobRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        createChannels()
        scope.launch { eventBus.flow.collect { notify(it) } }
    }

    private suspend fun notify(event: PrintJobEvent) {
        val job = printJobRepository.getById(event.jobId)
        val name = PrintStatusMessages.baseName(job?.documentName ?: "Impressão")
        val queued = printJobRepository.observeActive().first().size
        val message = PrintStatusMessages.of(event, name, queued) ?: return
        post((event.groupId ?: event.jobId).hashCode(), message)
    }

    private fun post(id: Int, message: PrintStatusMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // Sem permissão: as telas de Fila/Histórico continuam mostrando o status.
        }
        val failure = message.kind == NotificationKind.FAILURE
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (failure) CHANNEL_FAILURES else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(message.kind == NotificationKind.PROGRESS || message.kind == NotificationKind.RETRYING)
            .setPriority(if (failure) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .apply { if (message.kind == NotificationKind.SUCCESS) setTimeoutAfter(SUCCESS_TIMEOUT_MS) }
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Status de impressão", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Confirmação de envio e novas tentativas"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FAILURES, "Falhas de impressão", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Impressões que não chegaram à impressora"
            },
        )
    }

    companion object {
        const val CHANNEL_STATUS = "print_status"
        const val CHANNEL_FAILURES = "print_failures"
        private const val SUCCESS_TIMEOUT_MS = 8_000L
    }
}
