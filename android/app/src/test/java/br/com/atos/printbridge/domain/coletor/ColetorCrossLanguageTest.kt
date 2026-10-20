package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.model.PrinterLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Teste cruzado TypeScript × Kotlin (passo 6c). A fixture é gerada pelo PRÓPRIO print-server
 * (designer/apps/print-server/scripts/gen-coletor-fixture.ts): pacote assinado de verdade, casos de
 * montagem e de lacuna calculados pela referência TS. Aqui o app tem de chegar exatamente nos
 * mesmos bytes e nas mesmas recusas — é o que garante que a etiqueta do coletor sai igual à do PC.
 */
class ColetorCrossLanguageTest {

    @Suppress("UNCHECKED_CAST")
    private val fixture: Map<String, Any?> by lazy {
        val text = javaClass.getResource("/coletor/fixture.json")!!.readText(Charsets.UTF_8)
        StrictJson.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private val envelope get() = fixture["envelope"] as Map<String, Any?>
    private val kid get() = fixture["kid"] as String
    private val spki get() = Base64.getDecoder().decode(fixture["publicKeySpkiB64"] as String)
    private val verifier get() = ColetorPackageVerifier(kid, spki)

    private fun envelopeJson(packageJson: String = envelope["packageJson"] as String, sha: String = envelope["packageSha256"] as String, kidOverride: String = kid): String {
        @Suppress("UNCHECKED_CAST")
        val sig = envelope["signature"] as Map<String, Any?>
        return """{"packageJson":${quote(packageJson)},"packageSha256":"$sha","signature":{"alg":"ES256","kid":"$kidOverride","value":"${sig["value"]}"}}"""
    }

    @Test
    fun `envelope do print-server passa por hash, assinatura e vetores`() {
        val v = verifier.verify(envelopeJson())
        assertEquals(envelope["packageSha256"], v.packageSha256)
        val pkg = v.pkg
        assertEquals("fixture-gondola", pkg.templateId)
        assertEquals("Fixture coletor — Gôndola", pkg.name)
        assertEquals(7, pkg.templateVersion)
        assertEquals(listOf("ZPL/203", "ZPL/300", "TSPL/203", "TSPL/300"), pkg.targets.map { "${it.language}/${it.dpi}" })
        assertEquals(listOf("LOTE", "TURNO", "DESC", "EAN", "EAN8", "UPC", "SKU"), pkg.fields.map { it.name })
        // TSPL leva BITMAP binário dentro de um lit: bytes acima de 0x7F atravessaram o JSON intactos.
        val tsplLit = pkg.targets[2].segments.filterIsInstance<Segment.Lit>().joinToString("") { it.text }
        assertTrue(tsplLit.contains("BITMAP ") && tsplLit.any { it.code in 0x80..0xFF })
    }

    @Test
    fun `montagem bate byte a byte com a referencia TS`() {
        val pkg = verifier.verify(envelopeJson()).pkg
        @Suppress("UNCHECKED_CAST")
        val cases = fixture["cases"] as List<Map<String, Any?>>
        assertEquals(12, cases.size)
        for (c in cases) {
            val target = pkg.targets[(c["target"] as Long).toInt()]
            @Suppress("UNCHECKED_CAST")
            val values = c["values"] as Map<String, String>
            val expected = c["result"] as String
            val got = try {
                ColetorPackageVerifier.sha256Hex(ColetorAssembler.assemble(target, values, (c["copies"] as Long).toInt()))
            } catch (e: SlotValueException) {
                "${e.code}:${e.field}"
            }
            assertEquals("alvo ${target.language}/${target.dpi} valores $values", expected, got)
        }
    }

    @Test
    fun `cada lacuna valida, normaliza e escapa igual ao TS`() {
        @Suppress("UNCHECKED_CAST")
        val slots = (fixture["slots"] as List<Map<String, Any?>>).associate { s ->
            s["id"] as String to PackageSlot(s["id"] as String, s["field"] as String, SlotKind.valueOf(s["kind"] as String), (s["maxLength"] as Long).toInt())
        }
        @Suppress("UNCHECKED_CAST")
        val cases = fixture["slotCases"] as List<Map<String, Any?>>
        assertTrue(cases.size > 300)
        val failures = cases.mapNotNull { c ->
            val slot = slots.getValue(c["slot"] as String)
            val language = PrinterLanguage.valueOf(c["language"] as String)
            val value = c["value"] as String
            val got = try {
                "OK:" + ColetorAssembler.fillSlot(slot, value, language)
            } catch (e: SlotValueException) {
                e.code.name
            }
            if (got == c["result"]) null else "${slot.kind}/$language ${quote(value)}: TS=${c["result"]} Kotlin=$got"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun `pacote alterado e recusado antes de qualquer impressao`() {
        val json = envelope["packageJson"] as String
        val tampered = json.replace("Gôndola", "Gondola")

        expectReason(ColetorPackageException.Reason.HASH) { verifier.verify(envelopeJson(packageJson = tampered)) }
        // Quem altera o pacote E recalcula o hash ainda esbarra na assinatura.
        val sha = ColetorPackageVerifier.sha256Hex(tampered.toByteArray(Charsets.UTF_8))
        expectReason(ColetorPackageException.Reason.SIGNATURE) { verifier.verify(envelopeJson(packageJson = tampered, sha = sha)) }
        expectReason(ColetorPackageException.Reason.SIGNATURE) { verifier.verify(envelopeJson(kidOverride = "outra-chave")) }

        // Chave pública de outro PC (mesmo kid declarado): assinatura não confere.
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        expectReason(ColetorPackageException.Reason.SIGNATURE) { ColetorPackageVerifier(kid, other.public.encoded).verify(envelopeJson()) }

        expectReason(ColetorPackageException.Reason.MALFORMED) { verifier.verify("{\"packageJson\":1}") }
        expectReason(ColetorPackageException.Reason.MALFORMED) { verifier.verify("não é json") }
    }

    @Test
    fun `vetor que nao bate e formato desconhecido sao recusados`() {
        val json = envelope["packageJson"] as String
        val pkg = LabelPackage.parse(json)
        val t = pkg.targets[0]
        val wrong = t.copy(conformance = t.conformance.mapIndexed { i, v -> if (i == 0) v.copy(bytesSha256 = "0".repeat(64)) else v })
        expectReason(ColetorPackageException.Reason.CONFORMANCE) { ColetorPackageVerifier.checkConformance(pkg.copy(targets = listOf(wrong))) }

        expectReason(ColetorPackageException.Reason.FORMAT) { LabelPackage.parse(json.replace("\"formatVersion\":1", "\"formatVersion\":2")) }
        expectReason(ColetorPackageException.Reason.FORMAT) { LabelPackage.parse(json.replace("athos-label-coletor", "outro")) }
        expectReason(ColetorPackageException.Reason.MALFORMED) { LabelPackage.parse(json.replace("\"slot\":\"s1\"", "\"slot\":\"s999\"")) }
    }

    @Test
    fun `copias fora de 1 a 9999 sao recusadas`() {
        val t = verifier.verify(envelopeJson()).pkg.targets[2]
        val values = t.conformance[0].values
        for (bad in listOf(0, 10_000)) {
            try {
                ColetorAssembler.assemble(t, values, bad)
                fail("aceitou $bad cópias")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(ColetorAssembler.assemble(t, values, 12).toString(Charsets.ISO_8859_1).endsWith("PRINT 1,12\r\n"))
    }

    private fun expectReason(reason: ColetorPackageException.Reason, block: () -> Unit) {
        try {
            block()
            fail("esperava recusa $reason")
        } catch (e: ColetorPackageException) {
            assertEquals(e.message, reason, e.reason)
        }
    }

    /** String JSON com escape (para remontar o envelope nos testes). */
    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
