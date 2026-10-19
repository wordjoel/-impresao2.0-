package br.com.atos.printbridge.domain.printing

import br.com.atos.printbridge.domain.model.Printer

sealed class TransportResult {
    data class Success(val bytesSent: Int, val durationMillis: Long) : TransportResult()
    data class Failure(val reason: TransportFailureReason, val technicalMessage: String) : TransportResult()
}

enum class TransportFailureReason(val operatorMessage: String) {
    INVALID_ADDRESS("Endereço inválido"),
    NO_NETWORK("Dispositivo Android sem conexão com a rede"),
    CONNECTION_REFUSED("Conexão recusada"),
    TIMEOUT("Tempo de conexão esgotado"),
    IO_ERROR("Falha ao enviar dados para a impressora"),
    /** Conectou e falou o protocolo, mas a impressora recusou (ex.: status IPP de erro). */
    PRINTER_REJECTED("A impressora recusou o trabalho"),
    /** O documento não pode ir pelo protocolo da impressora (ex.: ZPL para uma impressora IPP). */
    PROTOCOL_MISMATCH("Documento incompatível com o protocolo da impressora"),
    /** Algo respondeu na porta, mas não é o serviço de impressão esperado. */
    NOT_A_PRINT_SERVICE("O endereço respondeu, mas não é um serviço de impressão"),
    UNKNOWN("Erro desconhecido"),
}

/**
 * Abstrai o meio físico de envio de bytes para a impressora. A V1 implementa apenas TCP
 * (TcpPrinterTransport); Bluetooth e USB são extensões futuras que implementam esta mesma
 * interface sem alterar PrintEngine ou a fila.
 */
interface PrinterTransport {
    suspend fun send(printer: Printer, payload: ByteArray): TransportResult
}

/** Testa apenas alcançabilidade (abre e fecha o socket), sem enviar payload de impressão real. */
interface PrinterConnectionTester {
    suspend fun test(printer: Printer): ConnectionTestResult

    /**
     * Probe por endereço/porta com timeout explícito — usado pela varredura de subnet (Fase 5),
     * que precisa de timeout curto por host. Não revalida o estado da rede (o chamador já o fez)
     * e nunca envia payload de impressão. Mesma implementação de socket de [test], sem duplicar
     * a lógica de TCP.
     */
    suspend fun testAddress(ipAddress: String, port: Int, timeoutMillis: Int): ConnectionTestResult
}

data class ConnectionTestResult(
    val reachable: Boolean,
    val message: String,
    val elapsedMillis: Long,
)

/**
 * Envio de documento por protocolo de impressão (IPP): precisa do job inteiro — formato do
 * documento, nome e cópias viram atributos da operação, não bytes repetidos.
 */
interface IppJobTransport {
    suspend fun send(printer: Printer, job: br.com.atos.printbridge.domain.model.PrintJob): TransportResult

    /** Sem IPP configurado (testes que só exercitam o socket RAW): falha explícita, nunca sucesso falso. */
    object Unavailable : IppJobTransport {
        override suspend fun send(printer: Printer, job: br.com.atos.printbridge.domain.model.PrintJob): TransportResult =
            TransportResult.Failure(TransportFailureReason.UNKNOWN, "Transporte IPP não disponível")
    }
}
