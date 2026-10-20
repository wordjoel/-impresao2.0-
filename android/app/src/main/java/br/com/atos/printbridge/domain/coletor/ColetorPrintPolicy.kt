package br.com.atos.printbridge.domain.coletor

import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel

/**
 * Regras de impressão de um template do designer pela ponte, para QUALQUER app chamador (tela do
 * IMPRESSÃO A ou outro app pela API local). Puras: sem rede nem relógio — quem chama informa o
 * resultado da conferência e o relógio monotônico do aparelho.
 */
object ColetorPrintPolicy {

    /** Decisão P1 (2026-10-20): sem resposta do PC, vale a última conferência de até 15 min. */
    const val OFFLINE_GRACE_MS = 15 * 60 * 1000L

    /** Resultado de GET /coletor/v1/labels/{id}/version. */
    sealed interface VersionCheck {
        data class Answered(val templateVersion: Int, val packageSha256: String) : VersionCheck
        /** 404: despublicado no PC. */
        data object NotPublished : VersionCheck
        /** 401: coletor revogado ou token inválido. Nunca entra na janela offline. */
        data object Unauthorized : VersionCheck
        /** Sem resposta em 3 s, erro de rede ou 5xx. */
        data object Unreachable : VersionCheck
    }

    /**
     * Última conferência BEM-SUCEDIDA deste template. [elapsedRealtimeMs] é o relógio monotônico
     * (SystemClock.elapsedRealtime), que a troca de hora não altera; [bootId] identifica o boot
     * (ex.: Settings.Global.BOOT_COUNT) porque esse relógio recomeça do zero ao religar.
     */
    data class Confirmation(val packageSha256: String, val elapsedRealtimeMs: Long, val bootId: String)

    enum class VersionCheckResult { CONFIRMED, OFFLINE_GRACE }

    sealed interface Decision {
        data class Print(val versionCheck: VersionCheckResult) : Decision
        /** O PC tem outro pacote: baixar, validar e só então imprimir ("atualizado para vN"). */
        data class Update(val templateVersion: Int, val packageSha256: String) : Decision
        data class Refuse(val reason: RefuseReason, val message: String) : Decision
    }

    enum class RefuseReason { UNPUBLISHED, UNAUTHORIZED, VERSION_UNCONFIRMED }

    const val MSG_UNCONFIRMED = "Sem conexão com o PC das etiquetas: não dá para confirmar a versão. Nada foi impresso."

    fun decide(
        storedSha256: String,
        check: VersionCheck,
        last: Confirmation?,
        nowElapsedRealtimeMs: Long,
        bootId: String,
    ): Decision = when (check) {
        is VersionCheck.Answered ->
            if (check.packageSha256 == storedSha256) {
                Decision.Print(VersionCheckResult.CONFIRMED)
            } else {
                Decision.Update(check.templateVersion, check.packageSha256)
            }
        VersionCheck.NotPublished -> Decision.Refuse(RefuseReason.UNPUBLISHED, "Template despublicado no PC. Nada foi impresso.")
        VersionCheck.Unauthorized -> Decision.Refuse(RefuseReason.UNAUTHORIZED, "Coletor não autorizado no PC das etiquetas (revogado?). Nada foi impresso.")
        VersionCheck.Unreachable -> {
            val age = last?.let { nowElapsedRealtimeMs - it.elapsedRealtimeMs }
            val inGrace = last != null &&
                last.packageSha256 == storedSha256 &&
                last.bootId == bootId &&
                age != null && age >= 0 && age < OFFLINE_GRACE_MS
            if (inGrace) Decision.Print(VersionCheckResult.OFFLINE_GRACE) else Decision.Refuse(RefuseReason.VERSION_UNCONFIRMED, MSG_UNCONFIRMED)
        }
    }

    /**
     * Alvo do pacote para a impressora cadastrada: linguagem e DPI iguais. Com linguagem AUTO,
     * TSPL para modelos TSC e ZPL para GENERIC_ZPL; modelo não informado não adivinha.
     * Null = o pacote não tem alvo para ela (LANGUAGE_MISMATCH com [missingTargetMessage]).
     */
    fun chooseTarget(pkg: LabelPackage, language: PrinterLanguage, model: PrinterModel, dpi: Int): PackageTarget? {
        val lang = effectiveLanguage(language, model) ?: return null
        return pkg.targets.firstOrNull { it.language == lang && it.dpi == dpi }
    }

    fun effectiveLanguage(language: PrinterLanguage, model: PrinterModel): PrinterLanguage? = when (language) {
        PrinterLanguage.ZPL, PrinterLanguage.TSPL -> language
        PrinterLanguage.AUTO -> when (model) {
            PrinterModel.TSC_ML241, PrinterModel.TSC_MB241, PrinterModel.OTHER_TSC -> PrinterLanguage.TSPL
            PrinterModel.GENERIC_ZPL -> PrinterLanguage.ZPL
            PrinterModel.UNKNOWN -> null
        }
    }

    fun missingTargetMessage(pkg: LabelPackage, language: PrinterLanguage, model: PrinterModel, dpi: Int): String {
        val have = pkg.targets.joinToString { "${it.language}/${it.dpi}" }
        val lang = effectiveLanguage(language, model)
            ?: return "Defina a linguagem (ZPL ou TSPL) da impressora: o modelo não foi informado. O pacote tem: $have"
        return "Publique o template para $lang/$dpi no designer. O pacote tem: $have"
    }
}
