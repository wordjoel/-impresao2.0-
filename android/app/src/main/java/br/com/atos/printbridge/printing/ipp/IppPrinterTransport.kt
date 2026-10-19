package br.com.atos.printbridge.printing.ipp

import br.com.atos.printbridge.domain.ipp.IppEndpoint
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppGateway
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.IppJobTransport
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import javax.inject.Inject

/**
 * Envio por IPP (Print-Job). O documento já está no formato que a impressora declarou aceitar
 * (PDF ou PWG Raster, escolhido na criação do job) — aqui só vai para a impressora, e cópias vão
 * pelo atributo `copies`, nunca repetindo o documento.
 */
class IppPrinterTransport @Inject constructor(
    private val gateway: IppGateway,
    private val settingsRepository: SettingsRepository,
    private val networkStateProvider: NetworkStateProvider,
) : IppJobTransport {

    override suspend fun send(printer: Printer, job: PrintJob): TransportResult {
        val format = job.type.ippDocumentFormat
            ?: return TransportResult.Failure(
                TransportFailureReason.PROTOCOL_MISMATCH,
                "Trabalho ${job.type} não pode ser enviado por IPP (a impressora ${printer.name} usa IPP)",
            )
        val path = printer.ippPath
            ?: return TransportResult.Failure(
                TransportFailureReason.INVALID_ADDRESS,
                "Caminho do serviço IPP não identificado para ${printer.ipAddress}:${printer.port} — refaça a descoberta",
            )
        if (!networkStateProvider.isNetworkAvailable()) {
            return TransportResult.Failure(TransportFailureReason.NO_NETWORK, "Sem rede no dispositivo Android")
        }

        val settings = settingsRepository.current()
        val document = job.payload.toByteArray(job.type.wireCharset)
        val startedAt = System.currentTimeMillis()
        val result = gateway.printJob(
            endpoint = IppEndpoint(printer.ipAddress, printer.port, path),
            documentFormat = format,
            jobName = job.documentName,
            copies = job.copies.coerceAtLeast(1),
            document = document,
            timeoutMillis = settings.connectTimeoutMillis.coerceAtLeast(settings.socketTimeoutMillis),
        )
        val elapsed = System.currentTimeMillis() - startedAt
        return when (result) {
            is IppResult.Ok -> {
                Logger.event(
                    LogLevel.INFO,
                    "ipp_print_job_accepted",
                    jobId = job.id,
                    printerId = printer.id,
                    extra = mapOf("ippJobId" to result.value.jobId, "bytes" to document.size, "format" to format, "elapsedMs" to elapsed),
                )
                TransportResult.Success(bytesSent = document.size, durationMillis = elapsed)
            }
            is IppResult.Failure -> {
                val reason = when (result.kind) {
                    IppFailureKind.UNREACHABLE -> TransportFailureReason.CONNECTION_REFUSED
                    IppFailureKind.TIMEOUT -> TransportFailureReason.TIMEOUT
                    IppFailureKind.NOT_IPP -> TransportFailureReason.NOT_A_PRINT_SERVICE
                    IppFailureKind.IPP_ERROR -> TransportFailureReason.PRINTER_REJECTED
                }
                TransportResult.Failure(reason, result.technicalMessage)
            }
        }
    }
}
