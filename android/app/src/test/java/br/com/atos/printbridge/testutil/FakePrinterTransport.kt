package br.com.atos.printbridge.testutil

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult

/**
 * Transporte fake para testar a fila/motor de impressão sem hardware físico (Fase 12).
 * Simula: sucesso, timeout, conexão recusada, erro intermitente e sucesso na Nª tentativa.
 */
class FakePrinterTransport(
    private val behavior: (attemptNumber: Int, payload: ByteArray) -> TransportResult,
) : PrinterTransport {

    val sentPayloads = mutableListOf<ByteArray>()
    private var callCount = 0

    override suspend fun send(printer: Printer, payload: ByteArray): TransportResult {
        callCount++
        sentPayloads.add(payload)
        return behavior(callCount, payload)
    }

    companion object {
        fun alwaysSucceeds() = FakePrinterTransport { _, payload ->
            TransportResult.Success(bytesSent = payload.size, durationMillis = 5)
        }

        fun alwaysFails(reason: TransportFailureReason = TransportFailureReason.CONNECTION_REFUSED) =
            FakePrinterTransport { _, _ -> TransportResult.Failure(reason, reason.operatorMessage) }

        fun succeedsOnAttempt(targetAttempt: Int) = FakePrinterTransport { attempt, payload ->
            if (attempt >= targetAttempt) {
                TransportResult.Success(bytesSent = payload.size, durationMillis = 5)
            } else {
                TransportResult.Failure(TransportFailureReason.TIMEOUT, "Tempo de conexão esgotado")
            }
        }
    }
}
