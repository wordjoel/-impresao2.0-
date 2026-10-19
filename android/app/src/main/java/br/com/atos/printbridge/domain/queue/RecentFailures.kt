package br.com.atos.printbridge.domain.queue

import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus

/**
 * "Falhas 24 h" da Home = falhas que AINDA pedem ação: trabalhos que falharam de vez nas últimas
 * [WINDOW_MILLIS] e não foram resolvidos. O histórico inteiro continua na tela Histórico; aqui só
 * sai do número o que não tem mais o que corrigir:
 *  - o mesmo documento foi impresso com sucesso depois (reimpressão ou reenvio, em qualquer impressora);
 *  - a impressora da falha não está mais cadastrada.
 * Tentar de novo pela Fila já tira o job de FAILED por si só.
 */
object RecentFailures {
    const val WINDOW_MILLIS = 24L * 60 * 60 * 1000

    fun count(
        jobs: List<PrintJob>,
        nowMillis: Long,
        registeredPrinterIds: Set<String>,
        windowMillis: Long = WINDOW_MILLIS,
    ): Int {
        val sent = jobs.filter { it.status == PrintJobStatus.SENT }
        val reprintedOk = sent.mapNotNullTo(HashSet()) { it.originalJobId }
        val lastSentByDocument = sent.groupBy { it.documentName }.mapValues { (_, list) -> list.maxOf { it.updatedAt } }
        return jobs.count { job ->
            job.status == PrintJobStatus.FAILED &&
                job.updatedAt >= nowMillis - windowMillis &&
                job.printerId in registeredPrinterIds &&
                job.id !in reprintedOk &&
                (lastSentByDocument[job.documentName] ?: Long.MIN_VALUE) <= job.updatedAt
        }
    }
}
