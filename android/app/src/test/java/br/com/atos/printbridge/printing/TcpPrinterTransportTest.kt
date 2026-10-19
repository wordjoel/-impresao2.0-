package br.com.atos.printbridge.printing

import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.printing.TransportFailureReason
import br.com.atos.printbridge.domain.printing.TransportResult
import br.com.atos.printbridge.testutil.FakeNetworkStateProvider
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Servidor TCP fake local (Fase 12): recebe os bytes enviados pelo TcpPrinterTransport e
 * permite verificar exatamente o payload, sem depender de uma impressora física.
 */
class TcpPrinterTransportTest {

    private val executor = Executors.newSingleThreadExecutor()

    private fun startFakeServer(): Pair<ServerSocket, Future<ByteArray>> {
        val serverSocket = ServerSocket(0)
        val future = executor.submit<ByteArray> {
            serverSocket.accept().use { client ->
                client.getInputStream().readBytes()
            }
        }
        return serverSocket to future
    }

    private fun printerFor(port: Int) = br.com.atos.printbridge.domain.model.Printer(
        id = "printer-1",
        storeId = null,
        name = "Expedição 01",
        model = PrinterModel.TSC_MB241,
        ipAddress = "127.0.0.1",
        port = port,
        protocol = PrinterProtocol.TCP,
        language = PrinterLanguage.ZPL,
        isDefault = true,
        enabled = true,
        lastSeenAt = null,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `sends exact ZPL bytes to the fake server`() = runTest {
        val (server, future) = startFakeServer()
        val transport = TcpPrinterTransport(
            settingsRepository = FakeSettingsRepository(AppSettings(connectTimeoutMillis = 2000, socketTimeoutMillis = 2000)),
            networkStateProvider = FakeNetworkStateProvider(available = true),
        )
        val payload = "^XA^FDATOS PRINT BRIDGE^FS^XZ".toByteArray(Charsets.UTF_8)

        val result = transport.send(printerFor(server.localPort), payload)
        val received = future.get()
        server.close()

        assertTrue(result is TransportResult.Success)
        assertArrayEquals(payload, received)
    }

    @Test
    fun `connection refused returns CONNECTION_REFUSED failure`() = runTest {
        // Porta sem listener nenhum (fechamos o server socket imediatamente antes de conectar).
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.close()

        val transport = TcpPrinterTransport(
            settingsRepository = FakeSettingsRepository(AppSettings(connectTimeoutMillis = 1000, socketTimeoutMillis = 1000)),
            networkStateProvider = FakeNetworkStateProvider(available = true),
        )

        val result = transport.send(printerFor(port), "^XA^XZ".toByteArray())

        assertTrue(result is TransportResult.Failure)
        assertEquals(TransportFailureReason.CONNECTION_REFUSED, (result as TransportResult.Failure).reason)
    }

    @Test
    fun `no network short-circuits without attempting to connect`() = runTest {
        val transport = TcpPrinterTransport(
            settingsRepository = FakeSettingsRepository(),
            networkStateProvider = FakeNetworkStateProvider(available = false),
        )

        val result = transport.send(printerFor(9100), "^XA^XZ".toByteArray())

        assertTrue(result is TransportResult.Failure)
        assertEquals(TransportFailureReason.NO_NETWORK, (result as TransportResult.Failure).reason)
    }

    @Test
    fun `invalid address is rejected before touching the network`() = runTest {
        val transport = TcpPrinterTransport(
            settingsRepository = FakeSettingsRepository(),
            networkStateProvider = FakeNetworkStateProvider(available = true),
        )

        val result = transport.send(printerFor(9100).copy(ipAddress = "999.999.1.1"), "^XA^XZ".toByteArray())

        assertTrue(result is TransportResult.Failure)
        assertEquals(TransportFailureReason.INVALID_ADDRESS, (result as TransportResult.Failure).reason)
    }
}
