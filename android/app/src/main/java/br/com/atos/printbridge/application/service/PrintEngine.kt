package br.com.atos.printbridge.application.service

import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.IppJobTransport
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import javax.inject.Inject

/**
 * Direciona um PrintJob ao transporte do protocolo da impressora: socket RAW (ZPL/TSPL, bytes
 * intactos) ou IPP (documento PDF/PWG Raster). A fila e os use cases de job não sabem qual é.
 */
class PrintEngine @Inject constructor(
    private val tcpTransport: PrinterTransport,
    private val ippTransport: IppJobTransport = IppJobTransport.Unavailable,
) {
    suspend fun send(printer: Printer, job: PrintJob): TransportResult = when (printer.protocol) {
        PrinterProtocol.TCP -> {
            if (job.type.ippDocumentFormat != null) {
                // PDF/PWG cru num socket RAW sairia como lixo ou nem sairia — recusa explícita.
                TransportResult.Failure(
                    TransportFailureReason.PROTOCOL_MISMATCH,
                    "Documento ${job.type} só pode ir por IPP; ${printer.name} está cadastrada como RAW",
                )
            } else {
                // ZPL/TSPL/RAW: bytes preservados exatamente como recebidos, nunca convertidos ou
                // rasterizados. copies > 1 repete o bloco de comandos em um único envio TCP.
                // TSPL sai em ISO-8859-1 para devolver intactos os bytes binários do BITMAP.
                val body = if (job.copies > 1) job.payload.repeat(job.copies) else job.payload
                tcpTransport.send(printer, body.toByteArray(job.type.wireCharset))
            }
        }
        PrinterProtocol.IPP -> ippTransport.send(printer, job)
        PrinterProtocol.BLUETOOTH ->
            TransportResult.Failure(TransportFailureReason.UNKNOWN, "Transporte Bluetooth ainda não implementado")
        PrinterProtocol.USB ->
            TransportResult.Failure(TransportFailureReason.UNKNOWN, "Transporte USB ainda não implementado")
    }
}
