package br.com.atos.printbridge.printing

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.ConnectionTestResult
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.validation.NetworkAddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * Só abre e fecha o socket (probe de alcançabilidade) — nunca escreve payload de impressão real,
 * conforme exigido: "não envie etiqueta real durante o simples teste de porta". A varredura de
 * subnet (Fase 5) reusa esta mesma lógica via [testAddress], apenas com timeout curto por host.
 */
class PrinterConnectionTesterImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkStateProvider: NetworkStateProvider,
) : PrinterConnectionTester {

    override suspend fun test(printer: Printer): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        if (!NetworkAddressValidator.isValidAddress(printer.ipAddress) || !NetworkAddressValidator.isValidPort(printer.port)) {
            return@withContext ConnectionTestResult(false, "Endereço inválido", elapsed(startedAt))
        }
        if (!networkStateProvider.isNetworkAvailable()) {
            return@withContext ConnectionTestResult(false, "Dispositivo Android sem conexão com a rede", elapsed(startedAt))
        }

        val settings = settingsRepository.current()
        probe(printer.ipAddress, printer.port, settings.connectTimeoutMillis, startedAt)
    }

    override suspend fun testAddress(ipAddress: String, port: Int, timeoutMillis: Int): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (!NetworkAddressValidator.isValidAddress(ipAddress) || !NetworkAddressValidator.isValidPort(port)) {
                return@withContext ConnectionTestResult(false, "Endereço inválido", elapsed(startedAt))
            }
            probe(ipAddress, port, timeoutMillis, startedAt)
        }

    private fun probe(ipAddress: String, port: Int, timeoutMillis: Int, startedAt: Long): ConnectionTestResult {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, port), timeoutMillis)
            ConnectionTestResult(true, "Impressora acessível", elapsed(startedAt))
        } catch (e: SocketTimeoutException) {
            ConnectionTestResult(false, "Tempo de conexão esgotado", elapsed(startedAt))
        } catch (e: ConnectException) {
            ConnectionTestResult(false, "Conexão recusada", elapsed(startedAt))
        } catch (e: UnknownHostException) {
            ConnectionTestResult(false, "Endereço inválido", elapsed(startedAt))
        } catch (e: IOException) {
            ConnectionTestResult(false, e.message ?: "Erro desconhecido", elapsed(startedAt))
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // best-effort
            }
        }
    }

    private fun elapsed(startedAt: Long) = System.currentTimeMillis() - startedAt
}
