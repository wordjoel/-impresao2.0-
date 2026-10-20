package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.Confirmation
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.Decision
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.RefuseReason
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheckResult
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColetorPrintPolicyTest {

    private val sha = "a".repeat(64)
    private val min = 60_000L
    private val last = Confirmation(sha, elapsedRealtimeMs = 100 * min, bootId = "7")

    private fun decide(check: VersionCheck, now: Long = 105 * min, conf: Confirmation? = last, boot: String = "7") =
        ColetorPrintPolicy.decide(sha, check, conf, now, boot)

    @Test
    fun `PC respondeu`() {
        assertEquals(Decision.Print(VersionCheckResult.CONFIRMED), decide(VersionCheck.Answered(3, sha)))
        assertEquals(Decision.Update(4, "b".repeat(64)), decide(VersionCheck.Answered(4, "b".repeat(64))))
        assertEquals(RefuseReason.UNPUBLISHED, (decide(VersionCheck.NotPublished) as Decision.Refuse).reason)
    }

    @Test
    fun `janela offline de 15 minutos pelo relogio monotonico`() {
        assertEquals(Decision.Print(VersionCheckResult.OFFLINE_GRACE), decide(VersionCheck.Unreachable, now = 100 * min))
        assertEquals(Decision.Print(VersionCheckResult.OFFLINE_GRACE), decide(VersionCheck.Unreachable, now = 115 * min - 1))
        assertEquals(RefuseReason.VERSION_UNCONFIRMED, (decide(VersionCheck.Unreachable, now = 115 * min) as Decision.Refuse).reason)
        // Nunca conferiu, conferiu outro pacote, religou, ou o relógio "voltou": não imprime.
        assertEquals(RefuseReason.VERSION_UNCONFIRMED, (decide(VersionCheck.Unreachable, conf = null) as Decision.Refuse).reason)
        assertEquals(RefuseReason.VERSION_UNCONFIRMED, (decide(VersionCheck.Unreachable, conf = last.copy(packageSha256 = "c".repeat(64))) as Decision.Refuse).reason)
        assertEquals(RefuseReason.VERSION_UNCONFIRMED, (decide(VersionCheck.Unreachable, boot = "8") as Decision.Refuse).reason)
        assertEquals(RefuseReason.VERSION_UNCONFIRMED, (decide(VersionCheck.Unreachable, now = 99 * min) as Decision.Refuse).reason)
        assertEquals(ColetorPrintPolicy.MSG_UNCONFIRMED, (decide(VersionCheck.Unreachable, conf = null) as Decision.Refuse).message)
    }

    @Test
    fun `coletor revogado nunca usa a janela offline`() {
        assertEquals(RefuseReason.UNAUTHORIZED, (decide(VersionCheck.Unauthorized, now = 101 * min) as Decision.Refuse).reason)
    }

    @Test
    fun `escolha do alvo pela impressora cadastrada`() {
        val t = { l: PrinterLanguage, d: Int -> PackageTarget(l, d, emptyList(), emptyList(), emptyList()) }
        val pkg = LabelPackage(1, "x", "x", 1, "h", 50.0, 30.0, "c", "t", emptyList(), listOf(t(PrinterLanguage.ZPL, 203), t(PrinterLanguage.TSPL, 203)))
        assertEquals(PrinterLanguage.TSPL, ColetorPrintPolicy.chooseTarget(pkg, PrinterLanguage.TSPL, PrinterModel.UNKNOWN, 203)?.language)
        assertEquals(PrinterLanguage.TSPL, ColetorPrintPolicy.chooseTarget(pkg, PrinterLanguage.AUTO, PrinterModel.TSC_MB241, 203)?.language)
        assertEquals(PrinterLanguage.ZPL, ColetorPrintPolicy.chooseTarget(pkg, PrinterLanguage.AUTO, PrinterModel.GENERIC_ZPL, 203)?.language)
        assertNull(ColetorPrintPolicy.chooseTarget(pkg, PrinterLanguage.AUTO, PrinterModel.UNKNOWN, 203))
        assertNull(ColetorPrintPolicy.chooseTarget(pkg, PrinterLanguage.TSPL, PrinterModel.TSC_MB241, 300))
        assertEquals(
            "Publique o template para TSPL/300 no designer. O pacote tem: ZPL/203, TSPL/203",
            ColetorPrintPolicy.missingTargetMessage(pkg, PrinterLanguage.TSPL, PrinterModel.TSC_MB241, 300),
        )
    }
}
