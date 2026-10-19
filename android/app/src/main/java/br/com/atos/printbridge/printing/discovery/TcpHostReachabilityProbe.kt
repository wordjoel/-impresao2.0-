package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.HostReachabilityProbe
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import javax.inject.Inject

/**
 * Probe usada pela varredura de subnet que REUSA a lógica de socket do
 * [PrinterConnectionTester] (Fase 5) — um único ponto de implementação de TCP. Só abre e fecha
 * o socket; nunca escreve payload de impressão.
 */
class TcpHostReachabilityProbe @Inject constructor(
    private val connectionTester: PrinterConnectionTester,
) : HostReachabilityProbe {

    override suspend fun probe(ipAddress: String, port: Int, timeoutMillis: Int): Boolean =
        connectionTester.testAddress(ipAddress, port, timeoutMillis).reachable
}