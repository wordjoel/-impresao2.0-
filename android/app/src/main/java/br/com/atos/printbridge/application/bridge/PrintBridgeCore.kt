package br.com.atos.printbridge.application.bridge

import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.JobStatus
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import br.com.atos.printbridge.application.usecase.EnqueuePrintJobUseCase
import br.com.atos.printbridge.application.usecase.SavePrinterUseCase
import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.ipp.IppDocumentPlan
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.raster.PwgRasterEncoder
import br.com.atos.printbridge.domain.raster.PwgRasterType
import br.com.atos.printbridge.domain.raster.asGraySource
import br.com.atos.printbridge.domain.raster.asRasterSource
import br.com.atos.printbridge.domain.error.InvalidMediaProfileException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PrintJobNotFoundException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.raster.DitherMode
import br.com.atos.printbridge.domain.raster.DocumentKind
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.domain.raster.RasterPageEncoder
import br.com.atos.printbridge.domain.raster.RasterTarget
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.template.BuiltInPrintTemplates
import br.com.atos.printbridge.domain.template.PlaceholderTemplateRenderer
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Resultado de printJob(): id a consultar depois (job avulso ou documento) + onde foi parar. */
data class SubmittedPrint(val id: String, val jobIds: List<String>, val printerId: String) {
    val pageCount: Int get() = jobIds.size
}

/**
 * Ponto único de entrada da Fase 1 — o "PrintBridgeCore". Faz o dispatch pelo tipo de documento
 * (seção 3 do prompt mestre) e SEMPRE termina persistindo na fila Room antes de qualquer envio:
 *
 *   TEMPLATE      → PlaceholderTemplateRenderer → ZPL/TSPL textual → 1 job
 *   PDF / IMAGE   → DocumentRasterizer → 1 bit → ^GFA/BITMAP → 1 job por página (grupo)
 *   RAW_ZPL_TSPL  → payload como veio → 1 job
 *
 * O envio acontece depois, pela fila (worker + backoff), igual para os três caminhos: não existe
 * atalho que mande direto ao socket sem passar pela persistência — se o app morrer no meio, o
 * job continua lá.
 */
class SubmitPrintRequestUseCase @Inject constructor(
    private val printerRepository: PrinterRepository,
    private val enqueue: EnqueuePrintJobUseCase,
    private val rasterizer: DocumentRasterizer,
) {
    suspend operator fun invoke(request: PrintJobRequest, sourceApp: String? = null): SubmittedPrint {
        val printer = resolvePrinter(request.printerProfileId)
        val name = request.documentName?.takeIf { it.isNotBlank() }

        if (printer.protocol == PrinterProtocol.IPP) {
            val jobs = submitToIpp(printer, request, name, sourceApp)
            return SubmittedPrint(id = jobs.first().groupId ?: jobs.first().id, jobIds = jobs.map { it.id }, printerId = printer.id)
        }

        val jobs: List<PrintJob> = when (request.documentType) {
            DocumentType.TEMPLATE -> {
                val templateId = requireArgument(request.templateId, "templateId é obrigatório para TEMPLATE")
                val template = BuiltInPrintTemplates.byId(templateId) ?: throw UnknownTemplateException(templateId)
                if (printer.language != PrinterLanguage.AUTO && printer.language != template.language) {
                    throw TemplateLanguageMismatchException(templateId, template.language.name, printer.language.name)
                }
                val payload = PlaceholderTemplateRenderer.render(template, request.variables.orEmpty())
                listOf(
                    enqueue(
                        printerId = printer.id,
                        type = template.language.toPayloadType(),
                        payload = payload,
                        documentName = name ?: template.name,
                        copies = request.copies,
                        externalDocumentId = request.externalDocumentId,
                        sourceApp = sourceApp,
                    ),
                )
            }

            DocumentType.RAW_ZPL_TSPL -> {
                val raw = requireArgument(request.rawPayload, "rawPayload é obrigatório para RAW_ZPL_TSPL")
                listOf(
                    enqueue(
                        printerId = printer.id,
                        type = printer.language.toPayloadType(),
                        payload = raw,
                        documentName = name ?: "raw-${printer.language.name.lowercase()}",
                        copies = request.copies,
                        externalDocumentId = request.externalDocumentId,
                        sourceApp = sourceApp,
                    ),
                )
            }

            DocumentType.PDF, DocumentType.IMAGE -> {
                val bytes = request.documentBytes
                    ?: throw DocumentRasterizationException("documentBytes/documentFd é obrigatório para ${request.documentType}")
                val kind = if (request.documentType == DocumentType.PDF) DocumentKind.PDF else DocumentKind.IMAGE
                val target = RasterTarget.forLabel(printer.labelWidthMm, printer.labelHeightMm, printer.dpi)
                val dither = when (request.ditherMode) {
                    br.com.atos.printbridge.api.DitherMode.THRESHOLD -> DitherMode.THRESHOLD
                    br.com.atos.printbridge.api.DitherMode.FLOYD_STEINBERG -> DitherMode.FLOYD_STEINBERG
                    // Default do prompt mestre: threshold para PDF (texto nítido), dithering para foto.
                    null -> if (kind == DocumentKind.PDF) DitherMode.THRESHOLD else DitherMode.FLOYD_STEINBERG
                }
                val pages = RasterPageEncoder.encode(rasterizer.rasterize(bytes, kind, target), printer.language, target, dither)
                enqueue.enqueuePages(
                    printerId = printer.id,
                    type = pages.first().language.toPayloadType(),
                    pages = pages.map { it.payload },
                    documentName = name ?: kind.name.lowercase(),
                    copies = request.copies,
                    externalDocumentId = request.externalDocumentId,
                    sourceApp = sourceApp,
                )
            }
        }

        val id = jobs.first().groupId ?: jobs.first().id
        return SubmittedPrint(id = id, jobIds = jobs.map { it.id }, printerId = printer.id)
    }

    /**
     * Impressora de escritório (IPP): o documento vai no formato que ELA declarou aceitar.
     *  - aceita PDF e o pedido é PDF → o PDF original vai intacto (melhor qualidade, sem rasterizar);
     *  - senão → PWG Raster no papel e dpi declarados, página a página, num único trabalho IPP.
     * Template/ZPL/TSPL são linguagens de etiqueta: recusados, nunca enviados como lixo.
     */
    private suspend fun submitToIpp(printer: Printer, request: PrintJobRequest, name: String?, sourceApp: String?): List<PrintJob> {
        if (request.documentType == DocumentType.TEMPLATE || request.documentType == DocumentType.RAW_ZPL_TSPL) {
            throw PrinterCapabilityException("${printer.name} é uma impressora IPP; ${request.documentType} (ZPL/TSPL) só vai para impressora de etiqueta RAW")
        }
        val bytes = request.documentBytes
            ?: throw DocumentRasterizationException("documentBytes/documentFd é obrigatório para ${request.documentType}")
        val plan = try {
            IppDocumentPlan.choose(printer.capabilities)
        } catch (e: IppDocumentPlan.Unsupported) {
            throw PrinterCapabilityException(e.reason)
        }
        val kind = if (request.documentType == DocumentType.PDF) DocumentKind.PDF else DocumentKind.IMAGE
        val docName = name ?: kind.name.lowercase()
        if (plan is IppDocumentPlan.Pdf && kind == DocumentKind.PDF) {
            return listOf(
                enqueue(printer.id, PrintPayloadType.PDF, String(bytes, Charsets.ISO_8859_1), docName, request.copies, request.externalDocumentId, sourceApp = sourceApp),
            )
        }
        val raster = plan as? IppDocumentPlan.PwgRaster
            ?: throw PrinterCapabilityException("a impressora aceita PDF mas não PWG Raster; imagens precisam de PWG Raster (formatos: ${printer.capabilities?.documentFormats?.joinToString()})")
        if (raster.dpi > IppDocumentPlan.MAX_RASTER_DPI) {
            throw PrinterCapabilityException("a impressora só aceita raster a ${raster.dpi} dpi; o Print Bridge rasteriza até ${IppDocumentPlan.MAX_RASTER_DPI} dpi")
        }
        val target = RasterTarget.forLabel(raster.media.widthMm.toInt(), raster.media.heightMm.toInt(), raster.dpi)
        val dither = when (request.ditherMode) {
            br.com.atos.printbridge.api.DitherMode.FLOYD_STEINBERG -> DitherMode.FLOYD_STEINBERG
            br.com.atos.printbridge.api.DitherMode.THRESHOLD -> DitherMode.THRESHOLD
            null -> if (kind == DocumentKind.PDF) DitherMode.THRESHOLD else DitherMode.FLOYD_STEINBERG
        }
        var stream: PwgRasterEncoder.Stream? = null
        rasterizer.rasterizePages(bytes, kind, target) { _, total, page ->
            val s = stream ?: PwgRasterEncoder.Stream(raster.type, raster.dpi, raster.media.name, total).also { stream = it }
            // black_1: a binarização da Fase 1 (threshold/dithering) decide o preto; sgray_8 vai em cinza.
            val source = if (raster.type == PwgRasterType.BLACK_1) dither.binarizer().binarize(page).asGraySource() else page.asRasterSource()
            s.addPage(source)
        }
        val encoded = stream?.toByteArray() ?: throw DocumentRasterizationException("documento sem páginas")
        return listOf(
            enqueue(printer.id, PrintPayloadType.PWG_RASTER, String(encoded, Charsets.ISO_8859_1), docName, request.copies, request.externalDocumentId, sourceApp = sourceApp),
        )
    }

    private suspend fun resolvePrinter(printerId: String?): Printer =
        if (printerId != null) {
            printerRepository.getById(printerId) ?: throw PrinterNotFoundException(printerId)
        } else {
            printerRepository.getDefault() ?: throw NoDefaultPrinterException()
        }

    private fun requireArgument(value: String?, message: String): String {
        if (value.isNullOrEmpty()) throw IllegalArgumentException(message)
        return value
    }
}

private fun PrinterLanguage.toPayloadType(): PrintPayloadType =
    if (this == PrinterLanguage.TSPL) PrintPayloadType.TSPL else PrintPayloadType.ZPL

/**
 * Status consolidado para getJobStatus(): aceita tanto o id de um job quanto o id de documento
 * (grupo) devolvido para PDF multipágina.
 */
class GetJobStatusUseCase @Inject constructor(
    private val printJobRepository: PrintJobRepository,
) {
    suspend operator fun invoke(id: String): JobStatus {
        printJobRepository.getById(id)?.let { return statusOf(it) }
        val pages = printJobRepository.getByGroupId(id)
        if (pages.isEmpty()) throw PrintJobNotFoundException(id)
        return statusOfDocument(pages)
    }

    private suspend fun statusOf(job: PrintJob): JobStatus = when (job.status) {
        PrintJobStatus.PENDING -> JobStatus.Queued(printJobRepository.countActiveAhead(job))
        PrintJobStatus.PROCESSING -> JobStatus.Sending(job.attempts)
        PrintJobStatus.SENT -> JobStatus.Completed((job.sentAt ?: job.updatedAt) - job.createdAt)
        PrintJobStatus.FAILED -> JobStatus.Failed(job.errorMessage ?: "Falha no envio", job.attempts)
        // Para o app cliente, "removido pelo operador" é um cancelamento (contrato AIDL inalterado).
        PrintJobStatus.CANCELLED, PrintJobStatus.DELETED_BY_OPERATOR -> JobStatus.Cancelled
    }

    private suspend fun statusOfDocument(pages: List<PrintJob>): JobStatus {
        // Ainda andando: o status é o da página da vez (a que está enviando, ou a próxima da fila).
        pages.firstOrNull { it.status == PrintJobStatus.PROCESSING }?.let { return statusOf(it) }
        pages.firstOrNull { it.status == PrintJobStatus.PENDING }?.let { return statusOf(it) }

        val total = pages.first().pageCount ?: pages.size
        val ok = pages.count { it.status == PrintJobStatus.SENT }
        return when {
            ok == total -> JobStatus.Completed(pages.maxOf { it.sentAt ?: it.updatedAt } - pages.minOf { it.createdAt })
            ok > 0 -> JobStatus.PartiallyCompleted(ok, total)
            pages.all { it.status == PrintJobStatus.CANCELLED || it.status == PrintJobStatus.DELETED_BY_OPERATOR } -> JobStatus.Cancelled
            else -> {
                val failed = pages.firstOrNull { it.status == PrintJobStatus.FAILED }
                JobStatus.Failed(failed?.errorMessage ?: "Falha no envio", failed?.attempts ?: 0)
            }
        }
    }
}

/** Printer Profile Manager exposto pela API local: o perfil do prompt ↔ a impressora do app. */
class PrinterProfileUseCases @Inject constructor(
    private val printerRepository: PrinterRepository,
    private val savePrinter: SavePrinterUseCase,
) {
    suspend fun register(profile: PrinterProfile): Printer {
        val language = runCatching { PrinterLanguage.valueOf(profile.protocol.trim().uppercase()) }.getOrNull()
            ?.takeIf { it != PrinterLanguage.AUTO }
            ?: throw InvalidMediaProfileException("protocolo '${profile.protocol}' inválido (use ZPL ou TSPL)")
        val (width, height) = PrinterProfile.parsePaperSize(profile.defaultPaperSize)
            ?: throw InvalidMediaProfileException("tamanho de papel '${profile.defaultPaperSize}' inválido (use LARGURAxALTURA em mm)")
        val existing = profile.id?.let { printerRepository.getById(it) }
        return savePrinter(
            id = profile.id,
            storeId = profile.storeId.ifBlank { null },
            name = profile.name,
            model = existing?.model ?: if (language == PrinterLanguage.TSPL) PrinterModel.OTHER_TSC else PrinterModel.GENERIC_ZPL,
            ipAddress = profile.ipAddress,
            port = profile.port,
            language = language,
            isDefault = profile.isDefault,
            enabled = existing?.enabled ?: true,
            dpi = profile.dpi,
            labelWidthMm = width,
            labelHeightMm = height,
        )
    }

    suspend fun list(storeId: String?): List<PrinterProfile> {
        val printers = if (storeId.isNullOrBlank()) {
            printerRepository.observeAll().first()
        } else {
            printerRepository.observeByStore(storeId).first()
        }
        return printers.map { it.toProfile() }
    }
}

fun Printer.toProfile() = PrinterProfile(
    id = id,
    storeId = storeId.orEmpty(),
    name = name,
    ipAddress = ipAddress,
    port = port,
    protocol = if (language == PrinterLanguage.AUTO) PrinterLanguage.ZPL.name else language.name,
    dpi = dpi,
    defaultPaperSize = "${labelWidthMm}x$labelHeightMm",
    isDefault = isDefault,
)
