package br.com.atos.printbridge.util

import br.com.atos.printbridge.domain.error.EmptyPayloadException
import br.com.atos.printbridge.domain.error.InvalidCopiesException
import br.com.atos.printbridge.domain.error.InvalidPortException
import br.com.atos.printbridge.domain.error.InvalidPrinterAddressException
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PayloadTooLargeException
import br.com.atos.printbridge.domain.error.PrintJobNotFoundException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTranslatorTest {

    @Test
    fun `every known domain exception maps to a non-empty operator message`() {
        val cases = listOf(
            EmptyPayloadException(),
            PayloadTooLargeException(999_999, 500_000),
            InvalidCopiesException(999, 100),
            NoDefaultPrinterException(),
            PrinterNotFoundException("printer-xyz"),
            PrintJobNotFoundException("job-xyz"),
            InvalidPrinterAddressException("not-an-ip"),
            InvalidPortException(70000),
            LicenseRestrictedException("RESTRICTED"),
            LicenseRestrictedException("NOT_ACTIVATED"),
        )

        cases.forEach { error ->
            val message = ErrorTranslator.translate(error)
            assertTrue("mensagem vazia para ${error::class.simpleName}", message.isNotBlank())
        }
    }

    @Test
    fun `unrecognized exception never leaks its raw message`() {
        val secretLookingMessage = "token=abc123 at com.internal.SqliteHelper.query(SqliteHelper.kt:42): SQLITE_CONSTRAINT"
        val unexpected = IllegalStateException(secretLookingMessage)

        val translated = ErrorTranslator.translate(unexpected)

        assertFalse(translated.contains("token"))
        assertFalse(translated.contains("abc123"))
        assertFalse(translated.contains("SqliteHelper"))
        assertFalse(translated.contains(".kt:"))
        assertEquals("Não foi possível concluir a operação. Tente novamente ou consulte Diagnóstico.", translated)
    }

    @Test
    fun `unrecognized exception with null message never crashes and still returns the generic fallback`() {
        val unexpected = NullPointerException()

        val translated = ErrorTranslator.translate(unexpected)

        assertEquals("Não foi possível concluir a operação. Tente novamente ou consulte Diagnóstico.", translated)
    }

    @Test
    fun `no translated message ever contains a stack trace frame marker`() {
        // Verificação estrutural adicional: nenhuma mensagem traduzida (conhecida ou não) contém
        // marcadores típicos de stack trace/qualified class name que indicariam vazamento interno.
        val allErrors = listOf<Throwable>(
            EmptyPayloadException(),
            PayloadTooLargeException(1, 1),
            InvalidCopiesException(1, 1),
            NoDefaultPrinterException(),
            PrinterNotFoundException("id"),
            PrintJobNotFoundException("id"),
            InvalidPrinterAddressException("id"),
            InvalidPortException(1),
            LicenseRestrictedException("RESTRICTED"),
            RuntimeException("br.com.atos.printbridge.SomeInternalClass crashed at line 88"),
        )

        allErrors.forEach { error ->
            val message = ErrorTranslator.translate(error)
            assertFalse("mensagem parece vazar nome de classe interno: $message", message.contains("br.com.atos"))
            assertFalse("mensagem parece vazar referência de linha de código: $message", message.contains(Regex(":\\d+\\)")))
        }
    }

    @Test
    fun `license restricted message distinguishes not activated from restricted`() {
        val notActivated = ErrorTranslator.translate(LicenseRestrictedException("NOT_ACTIVATED"))
        val restricted = ErrorTranslator.translate(LicenseRestrictedException("RESTRICTED"))

        assertTrue(notActivated.contains("ativado"))
        assertTrue(restricted.contains("licença"))
        assertFalse(notActivated == restricted)
    }
}
