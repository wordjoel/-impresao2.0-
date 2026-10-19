package br.com.atos.printbridge.domain.discovery

import br.com.atos.printbridge.domain.model.Printer

/** Uma impressora já cadastrada e o que a busca desta rodada encontrou no endereço dela (nulo = nada). */
data class RegisteredPrinterStatus(val printer: Printer, val found: DiscoveredPrinter?) {
    /** A busca achou o host e a porta cadastrada respondeu. */
    val responding: Boolean get() = found?.services?.any { it.port == printer.port } == true
}

/** O que a tela de busca mostra: as cadastradas (sempre, respondendo ou não) e as novas na rede. */
data class DiscoveryListing(
    val registered: List<RegisteredPrinterStatus>,
    val newOnNetwork: List<DiscoveredPrinter>,
)

object RegisteredPrinterMatch {
    /**
     * Cruza cadastro × busca pelo IP (a mesma chave de [DiscoveredPrinter.dedupeKey]). Host cujo IP já
     * está cadastrado nunca aparece como novo: "Adicionar" nele criaria uma impressora duplicada.
     * Cadastrada que não respondeu continua na lista — offline não é excluída.
     */
    fun list(registered: List<Printer>, discovered: List<DiscoveredPrinter>): DiscoveryListing {
        val byIp = discovered.associateBy { it.dedupeKey }
        val registeredIps = registered.mapTo(HashSet()) { it.ipAddress }
        return DiscoveryListing(
            registered = registered
                .sortedWith(compareByDescending<Printer> { it.isDefault }.thenBy { it.name.lowercase() })
                .map { RegisteredPrinterStatus(it, byIp[it.ipAddress]) },
            newOnNetwork = discovered.filterNot { it.dedupeKey in registeredIps },
        )
    }
}
