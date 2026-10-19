package br.com.atos.printbridge.printing.strategy

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.printing.TransportResult

/**
 * Ponto de extensão para PDF (Fase 8). Enviar bytes PDF crus por uma porta TCP 9100 não é
 * garantia de impressão em impressoras térmicas ZPL/TSPL — por isso PDF nunca compartilha
 * caminho de código com PrintEngine/TcpPrinterTransport sem uma decisão arquitetural explícita
 * validada com o hardware real. A V1 entra em produção só com ZPL; PDF fica desabilitado.
 */
interface PdfPrintStrategy {
    suspend fun print(printer: Printer, pdfBytes: ByteArray): TransportResult
}

class UnsupportedPdfStrategy : PdfPrintStrategy {
    override suspend fun print(printer: Printer, pdfBytes: ByteArray): TransportResult =
        TransportResult.Failure(
            reason = br.com.atos.printbridge.domain.printing.TransportFailureReason.UNKNOWN,
            technicalMessage = "Impressão de PDF não é suportada nesta versão (V1 é ZPL-only). " +
                "Nenhuma conversão automática foi aplicada — decisão arquitetural pendente.",
        )
}
