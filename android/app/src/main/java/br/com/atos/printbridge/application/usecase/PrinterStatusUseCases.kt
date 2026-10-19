package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.ipp.IppEndpoint
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppGateway
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import javax.inject.Inject

/**
 * Resultado de uma checagem real. [online] só é verdadeiro quando o serviço respondeu; [status]
 * é o texto da tela e [detail] carrega a causa técnica (nunca escondida).
 */
data class PrinterStatusReport(
    val online: Boolean,
    val status: String,
    val detail: String?,
    val elapsedMillis: Long,
    val checkedAt: Long,
)

/**
 * "Testar conexão": IPP → Get-Printer-Attributes (estado real da impressora, e atualiza
 * identidade/capacidades no cadastro); RAW → abre e fecha o socket (RAW não informa estado,
 * então o texto diz só que a porta aceitou conexão).
 */
class CheckPrinterStatusUseCase @Inject constructor(
    private val ippGateway: IppGateway,
    private val connectionTester: PrinterConnectionTester,
    private val printerRepository: PrinterRepository,
    private val settingsRepository: SettingsRepository,
    private val networkStateProvider: NetworkStateProvider,
) {
    suspend operator fun invoke(printer: Printer): PrinterStatusReport {
        val startedAt = System.currentTimeMillis()
        fun report(online: Boolean, status: String, detail: String?) =
            PrinterStatusReport(online, status, detail, System.currentTimeMillis() - startedAt, System.currentTimeMillis())

        if (!networkStateProvider.isNetworkAvailable()) {
            return report(false, "Sem rede no Android", "O dispositivo não está conectado a nenhuma rede")
        }

        val result = when (printer.protocol) {
            PrinterProtocol.IPP -> checkIpp(printer, ::report)
            PrinterProtocol.TCP -> {
                val tcp = connectionTester.test(printer)
                if (tcp.reachable) {
                    printerRepository.markSeen(printer.id)
                    report(true, "Porta RAW acessível", "${printer.ipAddress}:${printer.port} aceitou conexão TCP; o protocolo RAW não informa o estado da impressora")
                } else {
                    report(false, "Impressora offline", "${printer.ipAddress}:${printer.port} — ${tcp.message}")
                }
            }
            PrinterProtocol.BLUETOOTH, PrinterProtocol.USB -> report(false, "Protocolo não suportado", "${printer.protocol.label} não implementado")
        }
        Logger.event(
            LogLevel.INFO,
            "printer_status_checked",
            printerId = printer.id,
            extra = mapOf("online" to result.online, "status" to result.status, "elapsedMs" to result.elapsedMillis),
        )
        return result
    }

    private suspend fun checkIpp(
        printer: Printer,
        report: (Boolean, String, String?) -> PrinterStatusReport,
    ): PrinterStatusReport {
        val path = printer.ippPath ?: return report(false, "Serviço IPP não identificado", "Caminho IPP desconhecido; refaça a busca na rede")
        val timeout = settingsRepository.current().connectTimeoutMillis
        return when (val result = ippGateway.getPrinterAttributes(IppEndpoint(printer.ipAddress, printer.port, path), timeout)) {
            is IppResult.Ok -> {
                val d = result.value
                // O que a impressora acabou de declarar substitui o que estava salvo.
                printerRepository.upsert(
                    printer.copy(
                        manufacturer = d.manufacturer ?: printer.manufacturer,
                        modelName = d.model ?: printer.modelName,
                        capabilities = d.capabilities,
                        lastSeenAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val problems = d.problems.takeIf { it.isNotEmpty() }?.joinToString(", ")
                val accepting = d.acceptingJobs
                when {
                    d.state == 5 -> report(false, "Impressora parada", problems ?: "printer-state = stopped")
                    accepting == false -> report(false, "Impressora não está aceitando trabalhos", problems)
                    else -> report(true, "Conectada · ${d.stateLabel}", problems?.let { "Avisos da impressora: $it" })
                }
            }
            is IppResult.Failure -> when (result.kind) {
                IppFailureKind.UNREACHABLE -> report(false, "Impressora offline", "${printer.ipAddress}:${printer.port} — ${result.technicalMessage}")
                IppFailureKind.TIMEOUT -> report(false, "Impressora não respondeu", result.technicalMessage)
                IppFailureKind.NOT_IPP -> report(false, "Impressora encontrada, mas serviço de impressão não respondeu", result.technicalMessage)
                IppFailureKind.IPP_ERROR -> report(false, "Serviço de impressão respondeu com erro", result.technicalMessage)
            }
        }
    }
}
