package br.com.atos.printbridge.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova de forma automatizada a salvaguarda de código de redação de segredos do `Logger`
 * (CARREFOUR-PRINT-DATA-CLASSIFICATION.md §4 / CARREFOUR-CYBER-SECURITY-GATE.md H3): qualquer chave
 * de `extra` que bata com `SENSITIVE_KEY_PATTERN` (token|senha|password|secret|credential|adminpin|
 * licensetoken|devicetoken|rawtoken) tem o valor substituído por `[REDACTED]` ANTES de entrar no
 * ring buffer — ou seja, antes de poder ser exportado pelo DiagnosticsExporter ou escrito no logcat.
 *
 * O valor sensível nunca pode aparecer em `recentEvents()` — um evento que o vaze falha aqui.
 */
class LoggerRedactionTest {

    /** Isola o cenário pelo número de eventos produzidos durante o bloco, sem depender de um reset (não exposto). */
    private fun capturedEvents(block: () -> Unit): List<String> {
        val before = Logger.recentEvents().size
        block()
        return Logger.recentEvents().drop(before)
    }

    @Test
    fun key_matching_the_sensitive_pattern_has_its_value_redacted() {
        val cases = mapOf(
            "licenseToken" to "payload.assinatura.sensivel",
            "adminPin" to "1234",
            "password" to "super-secreto",
            "rawToken" to "x.y.z",
            "deviceToken" to "token-de-dispositivo",
            "credential" to "credencial-crua",
        )

        val line = capturedEvents {
            Logger.event(
                LogLevel.INFO,
                "test_redaction",
                extra = cases,
            )
        }.single()

        // Nenhum valor sensível vaza no buffer/diagnóstico.
        cases.values.forEach { secret ->
            assertFalse("Valor sensível não deveria aparecer: '$secret'", line.contains(secret))
        }
        // E o marcador de redação aparece para todos (um por chave sensível).
        assertTrue(line.contains("[REDACTED]"))
    }

    @Test
    fun key_not_matching_the_sensitive_pattern_keeps_its_value() {
        val line = capturedEvents {
            Logger.event(
                LogLevel.INFO,
                "test_redaction",
                extra = mapOf(
                    "documentName" to "etiqueta.txt",
                    "sizeBytes" to 42,
                    "printerId" to "printer-1",
                ),
            )
        }.single()

        assertTrue(line.contains("etiqueta.txt"))
        assertTrue(line.contains("42"))
        assertTrue(line.contains("printer-1"))
        assertFalse(line.contains("[REDACTED]"))
    }

    @Test
    fun redaction_matches_key_name_case_insensitively() {
        val line = capturedEvents {
            Logger.event(
                LogLevel.WARN,
                "test_redaction",
                extra = mapOf(
                    "LicenseToken" to "valor-com-maiusculas",
                    "DEVICETOKEN" to "outro-segredo",
                ),
            )
        }.single()

        assertFalse("Não deveria vazar valor de LicenseToken", line.contains("valor-com-maiusculas"))
        assertFalse("Não deveria vazar valor de DEVICETOKEN", line.contains("outro-segredo"))
        assertTrue(line.contains("[REDACTED]"))
    }

    @Test
    fun redaction_does_not_cover_payload_or_document_content() {
        // Garante que a redação NÃO é exagerada: payload ZPL (campo `payload`) e nomes de documento
        // continuam legíveis no diagnóstico — a redação é por CHAVE do campo, não por conteúdo.
        val line = capturedEvents {
            Logger.event(
                LogLevel.ERROR,
                "test_redaction",
                jobId = "job-1",
                extra = mapOf("payload" to "^XA^FDTeste^FS^XZ"),
            )
        }.single()

        assertTrue(line.contains("^XA^FDTeste^FS^XZ"))
        assertFalse(line.contains("[REDACTED]"))
    }
}