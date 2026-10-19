package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.queue.JobSource
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.ipp.IppDocumentPlan
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.TestPageContent
import br.com.atos.printbridge.domain.printing.TestPageRenderer
import br.com.atos.printbridge.domain.raster.PwgRasterEncoder
import br.com.atos.printbridge.domain.repository.PrinterRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * "Imprimir teste": gera um trabalho de teste no formato que ESTA impressora entende e o põe na
 * fila (vira evento real no Histórico).
 *  - IPP → PDF ou PWG Raster, conforme as capacidades declaradas (ver [IppDocumentPlan]);
 *  - RAW → etiqueta ZPL ou TSPL, conforme a linguagem escolhida no cadastro.
 * RAW com linguagem AUTO segue a regra de sempre do app: ZPL.
 */
class PrintTestPageUseCase @Inject constructor(
    private val printerRepository: PrinterRepository,
    private val enqueue: EnqueuePrintJobUseCase,
    private val renderer: TestPageRenderer,
) {
    suspend operator fun invoke(printerId: String?): PrintJob {
        val printer = if (printerId != null) {
            printerRepository.getById(printerId) ?: throw PrinterNotFoundException(printerId)
        } else {
            printerRepository.getDefault() ?: throw NoDefaultPrinterException()
        }
        val now = Date()
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(now)
        val name = "Teste Print Bridge $stamp"

        return when (printer.protocol) {
            PrinterProtocol.IPP -> {
                val plan = try {
                    IppDocumentPlan.choose(printer.capabilities)
                } catch (e: IppDocumentPlan.Unsupported) {
                    throw PrinterCapabilityException(e.reason)
                }
                val content = content(printer, stamp, plan)
                val (type, bytes) = when (plan) {
                    is IppDocumentPlan.Pdf -> PrintPayloadType.PDF to renderer.renderPdf(content, plan.media)
                    is IppDocumentPlan.PwgRaster -> PrintPayloadType.PWG_RASTER to PwgRasterEncoder.encode(
                        listOf(renderer.renderRaster(content, plan.media, plan.dpi)), plan.type, plan.dpi, plan.media.name,
                    )
                }
                enqueue(printer.id, type, String(bytes, Charsets.ISO_8859_1), name, sourceApp = JobSource.APP_TEST_PAGE)
            }
            PrinterProtocol.TCP -> when (printer.language) {
                // AUTO segue a regra do app inteiro (mapper, rasterização, API): tratada como ZPL — o mesmo
                // que a "etiqueta de teste" da Home sempre fez. TSPL vai em TSPL.
                PrinterLanguage.ZPL, PrinterLanguage.AUTO -> enqueue(printer.id, PrintPayloadType.ZPL, zplLabel(printer, stamp), name)
                PrinterLanguage.TSPL -> enqueue(printer.id, PrintPayloadType.TSPL, tsplLabel(printer, stamp), name)
            }
            PrinterProtocol.BLUETOOTH, PrinterProtocol.USB ->
                throw PrinterCapabilityException("protocolo ${printer.protocol.label} não implementado")
        }
    }

    private fun content(printer: Printer, stamp: String, plan: IppDocumentPlan): TestPageContent {
        val format = when (plan) {
            is IppDocumentPlan.Pdf -> "application/pdf"
            is IppDocumentPlan.PwgRaster -> "image/pwg-raster (${plan.type.keyword}, ${plan.dpi} dpi)"
        }
        return TestPageContent(
            title = "Atos Print Bridge — página de teste",
            lines = listOf(
                "Impressora: ${printer.name}",
                "Fabricante: ${printer.displayManufacturer ?: Printer.NOT_INFORMED}",
                "Modelo: ${printer.displayModel ?: Printer.NOT_INFORMED}",
                "Endereço: ${printer.ipAddress}:${printer.port}/${printer.ippPath.orEmpty()}",
                "Protocolo: ${printer.protocol.label}",
                "Formato: $format",
                "Papel: ${plan.media.label}",
                "Enviado em: $stamp",
                "Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})",
            ),
        )
    }

    /** Etiqueta ZPL no tamanho do perfil; o texto é ASCII puro (a página de código da TSC não garante acento). */
    private fun zplLabel(printer: Printer, stamp: String): String {
        val dots = { mm: Int -> (mm * printer.dpi / 25.4).toInt() }
        return """
            ^XA
            ^PW${dots(printer.labelWidthMm)}
            ^LL${dots(printer.labelHeightMm)}
            ^CF0,36
            ^FO30,30^FDATOS PRINT BRIDGE - TESTE^FS
            ^CF0,24
            ^FO30,80^FD${ascii(printer.name)}^FS
            ^FO30,115^FD${printer.ipAddress}:${printer.port} ZPL ${printer.dpi}dpi^FS
            ^FO30,150^FD$stamp^FS
            ^XZ
        """.trimIndent()
    }

    private fun tsplLabel(printer: Printer, stamp: String): String =
        "SIZE ${printer.labelWidthMm} mm,${printer.labelHeightMm} mm\r\n" +
            "CLS\r\n" +
            "TEXT 30,30,\"3\",0,1,1,\"ATOS PRINT BRIDGE - TESTE\"\r\n" +
            "TEXT 30,80,\"2\",0,1,1,\"${ascii(printer.name)}\"\r\n" +
            "TEXT 30,115,\"2\",0,1,1,\"${printer.ipAddress}:${printer.port} TSPL ${printer.dpi}dpi\"\r\n" +
            "TEXT 30,150,\"2\",0,1,1,\"$stamp\"\r\n" +
            "PRINT 1\r\n"

    /** Remove acentos e qualquer caractere que viraria comando (^ ~ " \) no ZPL/TSPL. */
    private fun ascii(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .filter { it.code in 32..126 && it !in "^~\"\\" }
            .take(40)
}
