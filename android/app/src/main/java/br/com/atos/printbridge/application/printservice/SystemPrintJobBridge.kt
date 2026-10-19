package br.com.atos.printbridge.application.printservice

import br.com.atos.printbridge.domain.queue.JobSource
import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.JobStatus
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.application.bridge.GetJobStatusUseCase
import br.com.atos.printbridge.application.bridge.SubmitPrintRequestUseCase
import br.com.atos.printbridge.application.bridge.SubmittedPrint
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.model.PwgMediaSize
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.PrinterRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Como uma impressora do Print Bridge aparece no diálogo "Imprimir" do Android (Fase 2). Parte
 * pura — o [android.printservice.PrintService] só traduz isto para PrinterInfo/MediaSize.
 *
 * Etiqueta (RAW): a única mídia é o papel do perfil — o Print Framework gera o PDF já no tamanho da
 * etiqueta e o rasterizador da Fase 1 encaixa 1:1. Escritório (IPP): as mídias, resoluções e cor
 * que a PRÓPRIA impressora declarou no Get-Printer-Attributes.
 */
data class SystemMedia(
    val id: String,
    val label: String,
    /** Milésimos de polegada — a unidade do android.print.PrintAttributes.MediaSize. */
    val widthMils: Int,
    val heightMils: Int,
)

data class SystemPrinterSpec(
    val localId: String,
    val name: String,
    val description: String,
    val medias: List<SystemMedia>,
    val defaultMediaId: String,
    val resolutionsDpi: List<Int>,
    val defaultDpi: Int,
    /** Só quando a impressora declara cor E recebe o PDF original (o raster gerado é monocromático). */
    val color: Boolean,
    val ipAddress: String,
    val port: Int,
)

object SystemPrinterSpecs {
    /** Nulo = impressora IPP que ainda não declarou papel/resolução (não dá para montar o diálogo). */
    fun of(printer: Printer): SystemPrinterSpec? = when (printer.protocol) {
        PrinterProtocol.IPP -> ofIpp(printer)
        else -> ofLabel(printer)
    }

    private fun ofLabel(printer: Printer): SystemPrinterSpec {
        val size = "${printer.labelWidthMm}x${printer.labelHeightMm} mm"
        // AUTO segue o default do resto do app (mapper de Printer): ZPL.
        val language = if (printer.language == PrinterLanguage.AUTO) PrinterLanguage.ZPL.name else printer.language.name
        val media = SystemMedia(
            id = "PB_${printer.labelWidthMm}X${printer.labelHeightMm}MM",
            label = "Etiqueta $size",
            widthMils = mmToMils(printer.labelWidthMm.toDouble()),
            heightMils = mmToMils(printer.labelHeightMm.toDouble()),
        )
        return SystemPrinterSpec(
            localId = printer.id,
            name = printer.name,
            description = "$language · $size · ${printer.dpi} dpi · ${printer.ipAddress}:${printer.port}",
            medias = listOf(media),
            defaultMediaId = media.id,
            resolutionsDpi = listOf(printer.dpi),
            defaultDpi = printer.dpi,
            color = false,
            ipAddress = printer.ipAddress,
            port = printer.port,
        )
    }

    private fun ofIpp(printer: Printer): SystemPrinterSpec? {
        val caps = printer.capabilities ?: return null
        val medias = caps.media.mapNotNull(PwgMediaSize::parse).distinctBy { it.name }.take(MAX_MEDIAS)
            .map { SystemMedia(it.name, it.label, mmToMils(it.widthMm), mmToMils(it.heightMm)) }
        if (medias.isEmpty()) return null
        val defaultMedia = medias.firstOrNull { it.id == caps.defaultMedia } ?: medias.first()
        val resolutions = caps.resolutionsDpi.ifEmpty { listOfNotNull(caps.defaultResolutionDpi) }
        if (resolutions.isEmpty()) return null
        val identity = listOfNotNull(printer.displayManufacturer, printer.displayModel).joinToString(" ").ifBlank { null }
        return SystemPrinterSpec(
            localId = printer.id,
            name = printer.name,
            description = listOfNotNull("IPP", identity, "${printer.ipAddress}:${printer.port}").joinToString(" · "),
            medias = medias,
            defaultMediaId = defaultMedia.id,
            resolutionsDpi = resolutions,
            defaultDpi = caps.defaultResolutionDpi?.takeIf { it in resolutions } ?: resolutions.first(),
            color = caps.isColor && caps.supportsPdf,
            ipAddress = printer.ipAddress,
            port = printer.port,
        )
    }

    fun mmToMils(mm: Double): Int = (mm * 1000 / 25.4).roundToInt()

    private const val MAX_MEDIAS = 30
}

/** Como o job do sistema termina, na linguagem do android.printservice.PrintJob. */
sealed interface SystemJobOutcome {
    data object Completed : SystemJobOutcome
    data class Failed(val reason: String) : SystemJobOutcome
    data object Cancelled : SystemJobOutcome
}

/**
 * Ponte entre um job do Print Framework e a fila da Fase 1. O PDF que o sistema entrega entra pelo
 * MESMO [SubmitPrintRequestUseCase] da API local — licença, rasterização, persistência na fila e
 * retry são os mesmos; não existe um segundo caminho até o socket.
 *
 * O job do sistema só é concluído quando a fila confirma a entrega (ou falha de vez): marcar
 * "concluído" ao enfileirar mostraria sucesso com a impressora desligada.
 */
class SystemPrintJobBridge @Inject constructor(
    private val printerRepository: PrinterRepository,
    private val printJobRepository: PrintJobRepository,
    private val submitPrintRequest: SubmitPrintRequestUseCase,
    private val getJobStatus: GetJobStatusUseCase,
) {
    /** Impressoras ativas, na ordem da tela de impressoras, com a padrão primeiro. */
    suspend fun printers(): List<SystemPrinterSpec> =
        printerRepository.observeAll().first()
            .filter { it.enabled }
            .sortedByDescending { it.isDefault }
            .mapNotNull(SystemPrinterSpecs::of)

    suspend fun printer(localId: String): SystemPrinterSpec? =
        printerRepository.getById(localId)?.takeIf { it.enabled }?.let(SystemPrinterSpecs::of)

    suspend fun submit(localPrinterId: String, pdf: ByteArray, copies: Int, documentName: String?): SubmittedPrint =
        submitPrintRequest(
            PrintJobRequest(
                documentType = DocumentType.PDF,
                documentBytes = pdf,
                printerProfileId = localPrinterId,
                documentName = documentName,
                copies = copies.coerceAtLeast(1),
            ),
            sourceApp = JobSource.ANDROID_PRINT,
        )

    /**
     * Acompanha a fila até um estado final. [onStillWaiting] é chamado UMA vez, se a entrega passar
     * de [waitingNoticeAfterMillis] — impressora desligada/fora da rede; o job continua na fila e
     * o serviço usa isso para marcar o job do sistema como bloqueado, com o motivo.
     */
    suspend fun awaitOutcome(
        submitted: SubmittedPrint,
        pollMillis: Long = POLL_MILLIS,
        waitingNoticeAfterMillis: Long = WAITING_NOTICE_AFTER_MILLIS,
        onStillWaiting: (String) -> Unit = {},
    ): SystemJobOutcome {
        var elapsed = 0L
        var noticed = false
        while (true) {
            when (val status = getJobStatus(submitted.id)) {
                is JobStatus.Completed -> return SystemJobOutcome.Completed
                is JobStatus.Cancelled -> return SystemJobOutcome.Cancelled
                is JobStatus.Failed -> return SystemJobOutcome.Failed(status.error)
                is JobStatus.PartiallyCompleted ->
                    return SystemJobOutcome.Failed("Só ${status.pagesOk} de ${status.pagesTotal} páginas foram impressas")
                is JobStatus.Queued, is JobStatus.Sending -> Unit
            }
            if (!noticed && elapsed >= waitingNoticeAfterMillis) {
                noticed = true
                onStillWaiting(WAITING_MESSAGE)
            }
            delay(pollMillis)
            elapsed += pollMillis
        }
    }

    /** Cancelar no diálogo do sistema tira da fila as páginas que ainda não saíram. */
    suspend fun cancel(submitted: SubmittedPrint) {
        submitted.jobIds.forEach { id ->
            val job = printJobRepository.getById(id) ?: return@forEach
            if (job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.FAILED) {
                printJobRepository.cancel(id)
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 1_000L
        const val WAITING_NOTICE_AFTER_MILLIS = 15_000L
        const val WAITING_MESSAGE = "Impressora sem resposta — o trabalho está na fila do Print Bridge e sai quando ela voltar"
    }
}
