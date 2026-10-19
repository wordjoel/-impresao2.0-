package br.com.atos.printbridge.domain.validation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAddressValidatorTest {

    @Test
    fun `valid IPv4 addresses are accepted`() {
        assertTrue(NetworkAddressValidator.isValidAddress("192.168.0.50"))
        assertTrue(NetworkAddressValidator.isValidAddress("10.0.0.1"))
        assertTrue(NetworkAddressValidator.isValidAddress("255.255.255.255"))
    }

    @Test
    fun `invalid IPv4 addresses are rejected`() {
        assertFalse(NetworkAddressValidator.isValidAddress("999.999.1.1"))
        assertFalse(NetworkAddressValidator.isValidAddress("256.1.1.1"))
        assertFalse(NetworkAddressValidator.isValidAddress(""))
        assertFalse(NetworkAddressValidator.isValidAddress("   "))
    }

    @Test
    fun `valid hostnames are accepted`() {
        assertTrue(NetworkAddressValidator.isValidAddress("printer-expedicao-01"))
        assertTrue(NetworkAddressValidator.isValidAddress("printer.local"))
    }

    @Test
    fun `valid ports are within 1 to 65535`() {
        assertTrue(NetworkAddressValidator.isValidPort(1))
        assertTrue(NetworkAddressValidator.isValidPort(9100))
        assertTrue(NetworkAddressValidator.isValidPort(65535))
    }

    @Test
    fun `invalid ports are outside range`() {
        assertFalse(NetworkAddressValidator.isValidPort(0))
        assertFalse(NetworkAddressValidator.isValidPort(-1))
        assertFalse(NetworkAddressValidator.isValidPort(65536))
    }
}
