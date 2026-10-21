package br.com.atos.printbridge.application.coletor

import br.com.atos.printbridge.domain.coletor.ColetorPackageVerifier
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheckResult
import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.TemplateVersionUnconfirmedException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.service.PrintBridgeResults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ColetorTemplateResolverTest {

    private val id = ColetorFixture.templateId
    private val min = 60_000L

    private fun printer(language: PrinterLanguage = PrinterLanguage.TSPL, model: PrinterModel = PrinterModel.TSC_MB241, dpi: Int = 203) = Printer(
        id = "p1", storeId = null, name = "MB241", model = model, ipAddress = "10.0.0.5", port = 9100, protocol = PrinterProtocol.TCP,
        language = language, isDefault = true, enabled = true, lastSeenAt = null, createdAt = 0, updatedAt = 0, dpi = dpi,
    )

    private class Env {
        val store = FakeColetorStore()
        val remote = FakeColetorRemote()
        val clock = FakeDeviceClock()
        val resolver = ColetorTemplateResolver(store, remote, clock)
    }

    private val sample get() = ColetorFixture.case(2, 1)

    @Test
    fun `primeira impressao baixa, valida, guarda e monta os bytes do PC`() = runTest {
        val e = Env()
        val (values, copies, expected) = sample
        val label = e.resolver.render(id, values, printer(), copies)!!
        assertEquals(expected, label.labelSha256)
        assertEquals(expected, ColetorPackageVerifier.sha256Hex(label.payload.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(PrintPayloadType.TSPL, label.type)
        assertEquals(VersionCheckResult.CONFIRMED, label.versionCheck)
        assertEquals("TSPL/203", label.target)
        assertEquals(7, label.templateVersion)
        assertEquals(ColetorFixture.envelopeJson, e.store.envelopes[id])
        assertEquals(ColetorFixture.sha, e.store.confirmations[id]!!.packageSha256)
        assertEquals(1, e.remote.envelopeCalls)
    }

    @Test
    fun `ZPL sai em UTF-8 com os mesmos bytes da referencia`() = runTest {
        val e = Env()
        val (values, copies, expected) = ColetorFixture.case(1, 3)
        val label = e.resolver.render(id, values, printer(PrinterLanguage.ZPL, PrinterModel.GENERIC_ZPL, 300), copies)!!
        assertEquals(PrintPayloadType.ZPL, label.type)
        assertEquals(expected, ColetorPackageVerifier.sha256Hex(label.payload.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `ja guardado e versao igual imprime sem baixar de novo`() = runTest {
        val e = Env()
        e.store.envelopes[id] = ColetorFixture.envelopeJson
        val label = e.resolver.render(id, sample.first, printer(), 1)!!
        assertEquals(VersionCheckResult.CONFIRMED, label.versionCheck)
        assertEquals(0, e.remote.envelopeCalls)
        assertEquals(1, e.remote.versionCalls)
    }

    @Test
    fun `sem rede vale a janela de 15 minutos e depois recusa`() = runTest {
        val e = Env()
        e.resolver.render(id, sample.first, printer(), 1) // confirma com o PC
        e.remote.versionAnswer = VersionCheck.Unreachable

        e.clock.elapsed += 14 * min
        assertEquals(VersionCheckResult.OFFLINE_GRACE, e.resolver.render(id, sample.first, printer(), 1)!!.versionCheck)

        e.clock.elapsed += 2 * min
        val err = assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
        assertEquals(ColetorPrintPolicy.MSG_UNCONFIRMED, err.message)
        assertEquals("TEMPLATE_VERSION_UNCONFIRMED", PrintBridgeResults.codeOf(err))
    }

    @Test
    fun `reboot zera a janela offline`() = runTest {
        val e = Env()
        e.resolver.render(id, sample.first, printer(), 1)
        e.remote.versionAnswer = VersionCheck.Unreachable
        e.clock.boot = "4"
        assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
    }

    @Test
    fun `coletor revogado nunca imprime, nem dentro da janela`() = runTest {
        val e = Env()
        e.resolver.render(id, sample.first, printer(), 1)
        e.remote.versionAnswer = VersionCheck.Unauthorized
        val err = assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
        assertEquals(ColetorTemplateResolver.MSG_UNAUTHORIZED, err.message)
    }

    @Test
    fun `versao nova no PC que nao confere com o anunciado nao imprime a antiga`() = runTest {
        val e = Env()
        e.store.envelopes[id] = ColetorFixture.envelopeJson
        // PC anuncia outro pacote, mas entrega o mesmo de antes (republicou no meio do caminho).
        e.remote.versionAnswer = VersionCheck.Answered(8, "b".repeat(64))
        assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
        // E se não consegue baixar: também não imprime a versão antiga.
        e.remote.envelopeAnswer = ColetorRemote.Envelope.Unreachable
        assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
    }

    @Test
    fun `despublicado no PC apaga o guardado e responde UNKNOWN_TEMPLATE`() = runTest {
        val e = Env()
        e.resolver.render(id, sample.first, printer(), 1)
        e.remote.versionAnswer = VersionCheck.NotPublished
        val err = assertThrows(UnknownTemplateException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
        assertTrue(err.message!!.contains("despublicado"))
        assertNull(e.store.envelopes[id])
    }

    @Test
    fun `nao e do designer quando nao ha ligacao, id estranho ou nunca baixado sem PC`() = runTest {
        assertNull(Env().apply { store.linkValue = null }.resolver.render(id, sample.first, printer(), 1))
        assertNull(Env().resolver.render("../fora", sample.first, printer(), 1))
        assertNull(Env().apply { remote.versionAnswer = VersionCheck.Unreachable }.resolver.render(id, sample.first, printer(), 1))
        assertNull(Env().apply { remote.versionAnswer = VersionCheck.NotPublished }.resolver.render(id, sample.first, printer(), 1))
    }

    @Test
    fun `pacote guardado mexido no aparelho e descartado e baixado de novo`() = runTest {
        val e = Env()
        e.store.envelopes[id] = ColetorFixture.envelopeJson.replace("Gôndola", "Gondola")
        val label = e.resolver.render(id, sample.first, printer(), 1)!!
        assertEquals(sample.third, label.labelSha256)
        assertEquals(1, e.remote.envelopeCalls)
        assertEquals(ColetorFixture.envelopeJson, e.store.envelopes[id])
    }

    @Test
    fun `chave de outro PC recusa o pacote`() = runTest {
        val e = Env().apply { store.linkValue = ColetorFixture.link(kid = "outra") }
        assertThrows(TemplateVersionUnconfirmedException::class.java) { kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(), 1) } }
        assertTrue(e.store.envelopes.isEmpty())
    }

    @Test
    fun `impressora sem alvo no pacote e LANGUAGE_MISMATCH`() = runTest {
        val e = Env()
        val err = assertThrows(TemplateLanguageMismatchException::class.java) {
            kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(dpi = 600), 1) }
        }
        assertTrue(err.message!!.contains("TSPL/600"))
        assertThrows(TemplateLanguageMismatchException::class.java) {
            kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first, printer(PrinterLanguage.AUTO, PrinterModel.UNKNOWN), 1) }
        }
        // AUTO + modelo TSC escolhe TSPL.
        assertEquals("TSPL/203", e.resolver.render(id, sample.first, printer(PrinterLanguage.AUTO, PrinterModel.TSC_MB241), 1)!!.target)
    }

    @Test
    fun `campos faltando vem todos de uma vez e valor invalido diz o campo`() = runTest {
        val e = Env()
        val err = assertThrows(MissingTemplateVariablesException::class.java) {
            kotlinx.coroutines.runBlocking { e.resolver.render(id, mapOf("LOTE" to "1"), printer(), 1) }
        }
        assertEquals(listOf("TURNO", "DESC", "EAN", "EAN8", "UPC", "SKU").sorted(), err.missing.sorted())
        val bad = assertThrows(InvalidTemplateValueException::class.java) {
            kotlinx.coroutines.runBlocking { e.resolver.render(id, sample.first + ("EAN" to "7891234567890"), printer(), 1) }
        }
        assertEquals("EAN", bad.variable)
        assertEquals("INVALID_VARIABLE_VALUE", PrintBridgeResults.codeOf(bad))
    }
}
