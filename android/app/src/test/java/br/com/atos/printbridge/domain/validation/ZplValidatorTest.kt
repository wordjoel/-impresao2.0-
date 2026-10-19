package br.com.atos.printbridge.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZplValidatorTest {

    private fun codes(payload: String): List<String> = ZplValidator.validate(payload).map { it.code }

    @Test
    fun `well formed label has no warnings`() {
        val payload = "^XA\n^FO50,50\n^FDOLA\n^FS\n^XZ"
        assertEquals(emptyList<Any>(), codes(payload))
    }

    @Test
    fun `blank payload produces no warnings`() {
        assertEquals(emptyList<Any>(), codes("   "))
    }

    @Test
    fun `missing start reports MISSING_BLOCK_START`() {
        val payload = "^FDOLA\n^FS\n^XZ"
        assertTrue(codes(payload).contains("MISSING_BLOCK_START"))
    }

    @Test
    fun `missing end reports MISSING_BLOCK_END`() {
        val payload = "^XA\n^FDOLA\n^FS\n"
        assertTrue(codes(payload).contains("MISSING_BLOCK_END"))
    }

    @Test
    fun `unbalanced blocks reports UNBALANCED_BLOCKS`() {
        val payload = "^XA\n^XA\n^XZ"
        assertTrue(codes(payload).contains("UNBALANCED_BLOCKS"))
    }

    @Test
    fun `trailing content after end reports TRAILING_CONTENT_AFTER_END`() {
        val payload = "^XA\n^XZ\nQUALQUER-COISA"
        assertTrue(codes(payload).contains("TRAILING_CONTENT_AFTER_END"))
    }

    @Test
    fun `valid payload does not report unbalanced for matching blocks`() {
        val payload = "^XA\n^XA\n^FD1\n^FS\n^FD2\n^FS\n^XZ\n^XZ"
        assertEquals(emptyList<Any>(), ZplValidator.validate(payload).filter { it.code == "UNBALANCED_BLOCKS" })
    }

    @Test
    fun `messages are operator friendly and unique per code`() {
        val warnings = ZplValidator.validate("^FDOLA\n^FS\n")
        assertTrue(warnings.isNotEmpty())
        assertEquals(warnings.size, warnings.map { it.code }.toSet().size)
        warnings.forEach { assertTrue(it.message.isNotBlank()) }
    }
}