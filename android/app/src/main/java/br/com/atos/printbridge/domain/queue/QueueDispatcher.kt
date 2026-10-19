package br.com.atos.printbridge.domain.queue

/** Sinaliza à infraestrutura (WorkManager) que há trabalho pendente a processar. */
interface QueueDispatcher {
    fun requestProcessing()
}
