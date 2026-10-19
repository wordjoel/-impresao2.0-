package br.com.atos.printbridge.domain.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `correct pin verifies against its own hash`() {
        val hash = PinHasher.hash("1903")
        assertTrue(PinHasher.verify("1903", hash))
    }

    @Test
    fun `wrong pin is rejected`() {
        val hash = PinHasher.hash("1903")
        assertFalse(PinHasher.verify("0000", hash))
    }

    @Test
    fun `hash never contains the plain pin`() {
        val hash = PinHasher.hash("1903")
        assertFalse(hash.contains("1903"))
    }

    @Test
    fun `hashing the same pin twice produces different salts`() {
        val hashA = PinHasher.hash("1903")
        val hashB = PinHasher.hash("1903")
        assertNotEquals(hashA, hashB)
        assertTrue(PinHasher.verify("1903", hashA))
        assertTrue(PinHasher.verify("1903", hashB))
    }

    @Test
    fun `malformed stored value never verifies`() {
        assertFalse(PinHasher.verify("1903", "not-a-valid-hash"))
    }
}
