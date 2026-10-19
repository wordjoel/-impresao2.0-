package br.com.atos.printbridge.domain.snmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnmpCodecTest {

    @Test
    fun `oid encoding follows BER including multi byte arcs`() {
        assertArrayEquals(byteArrayOf(0x2B, 6, 1, 2, 1, 1, 1, 0), SnmpCodec.encodeOid(SnmpCodec.OID_SYS_DESCR))
        assertArrayEquals(byteArrayOf(0x2B, 6, 1, 4, 1, 0x82.toByte(), 0x37), SnmpCodec.encodeOid("1.3.6.1.4.1.311"))
        assertEquals("1.3.6.1.4.1.311", SnmpCodec.decodeOid(SnmpCodec.encodeOid("1.3.6.1.4.1.311")))
    }

    @Test
    fun `get request has version 0 community public and null values`() {
        val bytes = SnmpCodec.encodeGet(1, listOf(SnmpCodec.OID_SYS_DESCR))
        val expected = byteArrayOf(
            0x30, 0x26,
            0x02, 0x01, 0x00,
            0x04, 0x06, 'p'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(),
            0xA0.toByte(), 0x19,
            0x02, 0x01, 0x01,
            0x02, 0x01, 0x00,
            0x02, 0x01, 0x00,
            0x30, 0x0E, 0x30, 0x0C, 0x06, 0x08, 0x2B, 6, 1, 2, 1, 1, 1, 0, 0x05, 0x00,
        )
        assertArrayEquals(expected, bytes)
    }

    /** Resposta montada como um agente real devolveria (GetResponse = 0xA2). */
    private fun response(requestId: Int, vararg values: Pair<String, String>): ByteArray {
        fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte(), v.size.toByte()) + v
        val vbs = values.map { (oid, text) -> tlv(0x30, tlv(0x06, SnmpCodec.encodeOid(oid)) + tlv(0x04, text.toByteArray())) }
            .fold(ByteArray(0)) { a, b -> a + b }
        val pdu = tlv(0xA2, tlv(0x02, byteArrayOf(requestId.toByte())) + tlv(0x02, byteArrayOf(0)) + tlv(0x02, byteArrayOf(0)) + tlv(0x30, vbs))
        return tlv(0x30, tlv(0x02, byteArrayOf(0)) + tlv(0x04, "public".toByteArray()) + pdu)
    }

    @Test
    fun `response values are decoded and identity is derived without guessing`() {
        val decoded = SnmpCodec.decodeGetResponse(
            response(9, SnmpCodec.OID_SYS_DESCR to "Samsung M2070 Series; V3.00.01.23", SnmpCodec.OID_HR_DEVICE_DESCR to "Samsung M2070 Series"),
        )
        assertEquals(9, decoded.requestId)
        assertEquals(0, decoded.errorStatus)
        val identity = SnmpIdentity(decoded.values[SnmpCodec.OID_SYS_DESCR], null, decoded.values[SnmpCodec.OID_HR_DEVICE_DESCR])
        assertEquals("Samsung", identity.manufacturer)
        assertEquals("M2070 Series", identity.model)
    }

    @Test
    fun `non printer description does not become a manufacturer`() {
        val router = SnmpIdentity(sysDescr = "Linux router 4.9.0", sysName = "gw", deviceDescr = null)
        assertNull(router.manufacturer)
        assertNull(router.model)
        assertEquals("Linux router 4.9.0", router.bestDescription)
    }
}
