package br.com.atos.printbridge.domain.snmp

import java.io.ByteArrayOutputStream

/**
 * SNMPv1 GET mínimo (BER) para identificar uma impressora SEM mandar nada para impressão:
 * `sysDescr`, `sysName` e `hrDeviceDescr` (Host Resources MIB, que impressoras de rede
 * preenchem com fabricante e modelo). Lógica pura, testada byte a byte.
 */
object SnmpCodec {
    const val OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0"
    const val OID_SYS_NAME = "1.3.6.1.2.1.1.5.0"
    const val OID_HR_DEVICE_DESCR = "1.3.6.1.2.1.25.3.2.1.3.1"

    private const val SEQUENCE = 0x30
    private const val INTEGER = 0x02
    private const val OCTET_STRING = 0x04
    private const val NULL = 0x05
    private const val OID = 0x06
    private const val GET_REQUEST = 0xA0
    private const val GET_RESPONSE = 0xA2

    fun encodeGet(requestId: Int, oids: List<String>, community: String = "public"): ByteArray {
        val varbinds = oids.map { tlv(SEQUENCE, tlv(OID, encodeOid(it)) + tlv(NULL, ByteArray(0))) }
        val pdu = tlv(
            GET_REQUEST,
            tlv(INTEGER, encodeInt(requestId)) +
                tlv(INTEGER, encodeInt(0)) +
                tlv(INTEGER, encodeInt(0)) +
                tlv(SEQUENCE, varbinds.fold(ByteArray(0)) { acc, b -> acc + b }),
        )
        return tlv(
            SEQUENCE,
            tlv(INTEGER, encodeInt(0)) + // versão 0 = SNMPv1
                tlv(OCTET_STRING, community.toByteArray(Charsets.US_ASCII)) +
                pdu,
        )
    }

    data class GetResponse(val requestId: Int, val errorStatus: Int, val values: Map<String, String>)

    class MalformedSnmpException(message: String) : Exception(message)

    /** Só valores OCTET STRING interessam (descrições); os demais tipos são ignorados. */
    fun decodeGetResponse(data: ByteArray): GetResponse {
        val root = Reader(data)
        val message = root.expect(SEQUENCE)
        message.expect(INTEGER) // versão
        message.expect(OCTET_STRING) // community
        val pdu = message.expect(GET_RESPONSE)
        val requestId = decodeInt(pdu.expectBytes(INTEGER))
        val errorStatus = decodeInt(pdu.expectBytes(INTEGER))
        pdu.expect(INTEGER) // error-index
        val list = pdu.expect(SEQUENCE)
        val values = linkedMapOf<String, String>()
        while (list.hasMore()) {
            val vb = list.expect(SEQUENCE)
            val oid = decodeOid(vb.expectBytes(OID))
            val (tag, value) = vb.next()
            if (tag == OCTET_STRING) {
                val text = String(value, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (text.isNotEmpty()) values[oid] = text
            }
        }
        return GetResponse(requestId, errorStatus, values)
    }

    // ------------------------------------------------------------------ BER

    private fun tlv(tag: Int, value: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + encodeLength(value.size) + value

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }

    private fun encodeInt(value: Int): ByteArray {
        var bytes = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        // Forma mínima do complemento de dois.
        while (bytes.size > 1 && ((bytes[0].toInt() == 0 && bytes[1] >= 0) || (bytes[0].toInt() == -1 && bytes[1] < 0))) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }

    private fun decodeInt(bytes: ByteArray): Int {
        if (bytes.isEmpty() || bytes.size > 4) throw MalformedSnmpException("inteiro inválido (${bytes.size} bytes)")
        var v = if (bytes[0] < 0) -1 else 0
        bytes.forEach { v = (v shl 8) or (it.toInt() and 0xFF) }
        return v
    }

    fun encodeOid(oid: String): ByteArray {
        val parts = oid.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID curto: $oid" }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        parts.drop(2).forEach { n ->
            val chunks = mutableListOf<Int>()
            var x = n
            do { chunks.add((x and 0x7F).toInt()); x = x shr 7 } while (x > 0)
            chunks.reversed().forEachIndexed { i, c -> out.write(if (i < chunks.size - 1) c or 0x80 else c) }
        }
        return out.toByteArray()
    }

    fun decodeOid(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw MalformedSnmpException("OID vazio")
        val first = bytes[0].toInt() and 0xFF
        val parts = mutableListOf((first / 40).toLong(), (first % 40).toLong())
        var acc = 0L
        for (i in 1 until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            acc = (acc shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) { parts.add(acc); acc = 0 }
        }
        return parts.joinToString(".")
    }

    private class Reader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
        fun hasMore() = pos < end

        fun next(): Pair<Int, ByteArray> {
            if (pos + 2 > end) throw MalformedSnmpException("TLV truncado")
            val tag = data[pos++].toInt() and 0xFF
            var len = data[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || n > 3 || pos + n > end) throw MalformedSnmpException("comprimento inválido")
                len = 0
                repeat(n) { len = (len shl 8) or (data[pos++].toInt() and 0xFF) }
            }
            if (pos + len > end) throw MalformedSnmpException("valor excede a mensagem")
            val value = data.copyOfRange(pos, pos + len)
            pos += len
            return tag to value
        }

        fun expectBytes(tag: Int): ByteArray {
            val (t, v) = next()
            if (t != tag) throw MalformedSnmpException("esperado tag 0x%02X, veio 0x%02X".format(tag, t))
            return v
        }

        fun expect(tag: Int): Reader = expectBytes(tag).let { Reader(it) }
    }
}
