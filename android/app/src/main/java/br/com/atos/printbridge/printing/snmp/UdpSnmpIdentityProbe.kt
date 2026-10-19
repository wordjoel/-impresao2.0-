package br.com.atos.printbridge.printing.snmp

import br.com.atos.printbridge.domain.snmp.SnmpCodec
import br.com.atos.printbridge.domain.snmp.SnmpIdentity
import br.com.atos.printbridge.domain.snmp.SnmpIdentityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** SNMPv1 GET na porta UDP 161, community "public" (leitura). Sem resposta = nulo, nunca inventa. */
class UdpSnmpIdentityProbe @Inject constructor() : SnmpIdentityProbe {

    private val ids = AtomicInteger(1)

    override suspend fun identify(ipAddress: String, timeoutMillis: Int): SnmpIdentity? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis
                val requestId = ids.getAndIncrement()
                val request = SnmpCodec.encodeGet(
                    requestId,
                    listOf(SnmpCodec.OID_SYS_DESCR, SnmpCodec.OID_SYS_NAME, SnmpCodec.OID_HR_DEVICE_DESCR),
                )
                val address = InetAddress.getByName(ipAddress)
                socket.send(DatagramPacket(request, request.size, address, 161))
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = SnmpCodec.decodeGetResponse(buffer.copyOf(packet.length))
                if (response.requestId != requestId) return@use null
                SnmpIdentity(
                    sysDescr = response.values[SnmpCodec.OID_SYS_DESCR],
                    sysName = response.values[SnmpCodec.OID_SYS_NAME],
                    deviceDescr = response.values[SnmpCodec.OID_HR_DEVICE_DESCR],
                ).takeIf { it.sysDescr != null || it.deviceDescr != null || it.sysName != null }
            }
        }.getOrNull()
    }
}
