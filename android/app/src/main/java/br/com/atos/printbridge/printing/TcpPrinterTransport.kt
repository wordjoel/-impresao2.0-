package br.com.atos.printbridge.printing

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.validation.NetworkAddressValidator
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
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
 * Implementação real de PrinterTransport sobre TCP puro. Abre o socket, escreve todos os bytes,
 * dá flush e fecha os recursos de forma determinística (try/finally), nunca deixando um socket
 * vazado em caso de exceção.
 */
class TcpPrinterTransport @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkStateProvider: NetworkStateProvider,
) : PrinterTransport {

    override suspend fun send(printer: Printer, payload: ByteArray): TransportResult = withContext(Dispatchers.IO) {
        if (!NetworkAddressValidator.isValidAddress(printer.ipAddress) || !NetworkAddressValidator.isValidPort(printer.port)) {
            return@withContext TransportResult.Failure(TransportFailureReason.INVALID_ADDRESS, "IP ou porta inválidos: ${printer.ipAddress}:${printer.port}")
        }
        if (!networkStateProvider.isNetworkAvailable()) {
            return@withContext TransportResult.Failure(TransportFailureReason.NO_NETWORK, "Sem rede no dispositivo Android")
        }

        val settings = settingsRepository.current()
        val startedAt = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = settings.socketTimeoutMillis
            socket.connect(InetSocketAddress(printer.ipAddress, printer.port), settings.connectTimeoutMillis)

            socket.getOutputStream().use { out ->
                out.write(payload)
                out.flush()
            }

            val elapsed = System.currentTimeMillis() - startedAt
            Logger.event(LogLevel.INFO, "tcp_send_success", printerId = printer.id, extra = mapOf("bytes" to payload.size, "elapsedMs" to elapsed))
            TransportResult.Success(bytesSent = payload.size, durationMillis = elapsed)
        } catch (e: SocketTimeoutException) {
            failure(printer.id, TransportFailureReason.TIMEOUT, e)
        } catch (e: ConnectException) {
            failure(printer.id, TransportFailureReason.CONNECTION_REFUSED, e)
        } catch (e: UnknownHostException) {
            failure(printer.id, TransportFailureReason.INVALID_ADDRESS, e)
        } catch (e: IOException) {
            failure(printer.id, TransportFailureReason.IO_ERROR, e)
        } catch (e: Exception) {
            failure(printer.id, TransportFailureReason.UNKNOWN, e)
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Fechamento best-effort; já capturamos e reportamos a falha original acima.
            }
        }
    }

    private fun failure(printerId: String, reason: TransportFailureReason, e: Exception): TransportResult.Failure {
        Logger.event(LogLevel.ERROR, "tcp_send_failure", printerId = printerId, extra = mapOf("reason" to reason.name, "exception" to (e.message ?: e::class.simpleName)))
        return TransportResult.Failure(reason, e.message ?: e::class.simpleName ?: "erro desconhecido")
    }
}
