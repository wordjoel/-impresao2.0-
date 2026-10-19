package br.com.atos.printbridge.domain.queue

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eventos de ciclo de vida de um job, emitidos pela fila no momento em que acontecem. É o que tira
 * o operador (e o app chamador) "do escuro" do fluxo antigo — Fase 1, Etapas 6 e 7.
 *
 * "Completed" segue a semântica de SENT: bytes entregues integralmente à impressora pelo socket.
 * A TSC não confirma impressão física de volta nesta fase.
 */
sealed class PrintJobEvent {
    abstract val jobId: String
    /** Documento multipágina ao qual o job pertence; nulo = job avulso. */
    abstract val groupId: String?

    data class Retrying(
        override val jobId: String,
        override val groupId: String?,
        val attempt: Int,
        val maxAttempts: Int,
        val error: String,
        val nextRetryInMillis: Long,
    ) : PrintJobEvent()

    data class Completed(
        override val jobId: String,
        override val groupId: String?,
        val durationMillis: Long,
        val attempts: Int,
    ) : PrintJobEvent()

    data class Failed(
        override val jobId: String,
        override val groupId: String?,
        val error: String,
        val attempts: Int,
    ) : PrintJobEvent()

    /** Página [pageNumber] (a partir de 1) de [pageCount] entregue à impressora. */
    data class PagePrinted(
        override val jobId: String,
        override val groupId: String,
        val pageNumber: Int,
        val pageCount: Int,
    ) : PrintJobEvent()

    /** Todas as páginas do documento chegaram a um estado final (enviadas ou falhas). */
    data class DocumentFinished(
        override val jobId: String,
        override val groupId: String,
        val pagesOk: Int,
        val pagesTotal: Int,
    ) : PrintJobEvent()
}

@Singleton
class PrintJobEventBus @Inject constructor() {
    // Buffer com descarte do mais antigo: a fila NUNCA pode travar esperando um ouvinte lento
    // (ex.: um app cliente que não drena os callbacks). Estado durável fica no Room, não aqui.
    private val events = MutableSharedFlow<PrintJobEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<PrintJobEvent> = events.asSharedFlow()

    fun emit(event: PrintJobEvent) {
        events.tryEmit(event)
    }
}
