package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveryRequest
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.discovery.PrinterDiscovery
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.printing.discovery.NsdServiceMapper
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Descoberta sob demanda: mDNS + varredura TCP nas portas de impressão + identificação (IPP,
 * SNMP). O Flow só vive enquanto coletado; cancelar encerra tudo.
 */
class DiscoverPrintersUseCase @Inject constructor(
    private val discovery: PrinterDiscovery,
    private val settingsRepository: br.com.atos.printbridge.domain.repository.SettingsRepository,
) {
    /** Portas registradas + a porta RAW extra das configurações (sondada como candidata RAW). */
    suspend operator fun invoke(): Flow<DiscoveredPrinter> {
        val extra = settingsRepository.current().discoveryPort
        val ports = (PrintServiceProtocol.SCAN_PORTS + extra).filter { it in 1..65535 }.distinct()
        return discovery.discover(DiscoveryRequest(ports = ports))
    }
}

/**
 * Cadastra um host descoberto usando o serviço que a PRÓPRIA descoberta confirmou (IPP
 * verificado, ou RAW anunciado). Nome, fabricante e modelo são os informados pela impressora;
 * sem nome informado, o cadastro usa o IP — nunca um modelo presumido.
 */
class SaveDiscoveredPrinterUseCase @Inject constructor(
    private val savePrinterUseCase: SavePrinterUseCase,
) {
    suspend operator fun invoke(discovered: DiscoveredPrinter, storeId: String? = null): Printer {
        val service = discovered.preferredService
            ?: throw PrinterCapabilityException(discovered.problem ?: "nenhum serviço de impressão confirmado em ${discovered.ipAddress}")
        val protocol = if (service.protocol == PrintServiceProtocol.IPP) PrinterProtocol.IPP else PrinterProtocol.TCP
        val title = listOfNotNull(discovered.manufacturer, discovered.model).joinToString(" ").ifBlank { null }
        return savePrinterUseCase(
            id = null,
            storeId = storeId,
            name = discovered.name?.takeIf { it.isNotBlank() } ?: title ?: "Impressora ${discovered.ipAddress}",
            // Família TSC reconhecida pelo que a impressora anunciou continua cadastrada como TSC.
            model = NsdServiceMapper.modelHint(listOfNotNull(discovered.manufacturer, discovered.model, discovered.name).joinToString(" "))
                ?: PrinterModel.UNKNOWN,
            ipAddress = discovered.ipAddress,
            port = service.port,
            // RAW: a linguagem (ZPL/TSPL) não se descobre sem imprimir — fica AUTO até o admin escolher.
            language = PrinterLanguage.AUTO,
            isDefault = false,
            enabled = true,
            protocol = protocol,
            identity = DiscoveredIdentity(
                manufacturer = discovered.manufacturer,
                model = discovered.model,
                hostname = discovered.hostname,
                ippPath = service.resourcePath.takeIf { protocol == PrinterProtocol.IPP },
                capabilities = discovered.capabilities.takeIf { protocol == PrinterProtocol.IPP },
            ),
        )
    }
}
