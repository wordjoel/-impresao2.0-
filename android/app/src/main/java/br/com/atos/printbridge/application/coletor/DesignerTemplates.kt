package br.com.atos.printbridge.application.coletor

import br.com.atos.printbridge.domain.coletor.ColetorAssembler
import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.domain.coletor.ColetorPackageException
import br.com.atos.printbridge.domain.coletor.ColetorPackageVerifier
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.Decision
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.RefuseReason
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheckResult
import br.com.atos.printbridge.domain.coletor.SlotValueException
import br.com.atos.printbridge.domain.coletor.VerifiedPackage
import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.TemplateVersionUnconfirmedException
import br.com.atos.printbridge.domain.error.UnknownTemplateException
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Etiqueta do designer pronta para a fila: bytes de UMA etiqueta já com as cópias da impressora. */
data class DesignerLabel(
    /** ZPL: o texto (vai em UTF-8). TSPL: 1 caractere = 1 byte (ISO-8859-1), como a fila já guarda. */
    val payload: String,
    val type: PrintPayloadType,
    val documentName: String,
    val templateVersion: Int,
    val packageSha256: String,
    val versionCheck: VersionCheckResult,
    val labelSha256: String,
    val target: String,
)

/**
 * Fonte de templates do designer do PC para `printJob(TEMPLATE)` (passo 6). Null = o templateId
 * não é do designer (o chamador segue com os embutidos e, sem nenhum, UNKNOWN_TEMPLATE).
 */
interface DesignerTemplateSource {
    suspend fun render(templateId: String, variables: Map<String, String>, printer: Printer, copies: Int): DesignerLabel?

    object None : DesignerTemplateSource {
        override suspend fun render(templateId: String, variables: Map<String, String>, printer: Printer, copies: Int): DesignerLabel? = null
    }
}

/** Rede do coletor → PC (GET /coletor/v1/...). Implementação: data/coletor/ColetorHttpRemote. */
interface ColetorRemote {
    fun version(link: ColetorLink, templateId: String): VersionCheck

    sealed interface Envelope {
        data class Ok(val json: String) : Envelope
        data object NotPublished : Envelope
        data object Unauthorized : Envelope
        data object Unreachable : Envelope
    }

    fun envelope(link: ColetorLink, templateId: String): Envelope
}

/** O que fica guardado no aparelho. Implementação: data/coletor/AndroidColetorStore. */
interface ColetorLocalStore {
    fun link(): ColetorLink?
    fun envelope(templateId: String): String?
    fun saveEnvelope(templateId: String, json: String)
    fun deleteEnvelope(templateId: String)
    fun confirmation(templateId: String): ColetorPrintPolicy.Confirmation?
    fun saveConfirmation(templateId: String, confirmation: ColetorPrintPolicy.Confirmation)
}

/** Relógio monotônico e identificação do boot (SystemClock.elapsedRealtime / BOOT_COUNT). */
interface DeviceClock {
    fun elapsedRealtimeMs(): Long
    fun bootId(): String
}

/**
 * Orquestra a impressão de um template do designer pela ponte, para QUALQUER app chamador:
 * pacote guardado (revalidado ao ler) → conferência de versão a cada impressão → atualização
 * quando o PC publicou outro → alvo da impressora → montagem. Regra de recusa em
 * [ColetorPrintPolicy]; erros saem como as exceções da API local (códigos estáveis).
 */
@Singleton
class ColetorTemplateResolver @Inject constructor(
    private val store: ColetorLocalStore,
    private val remote: ColetorRemote,
    private val clock: DeviceClock,
) : DesignerTemplateSource {

    /** Uma impressão de designer por vez: conferência + atualização não podem se cruzar. */
    private val mutex = Mutex()

    override suspend fun render(templateId: String, variables: Map<String, String>, printer: Printer, copies: Int): DesignerLabel? =
        mutex.withLock { renderLocked(templateId, variables, printer, copies) }

    private fun renderLocked(templateId: String, variables: Map<String, String>, printer: Printer, copies: Int): DesignerLabel? {
        if (!SAFE_ID.matches(templateId)) return null
        val link = store.link() ?: return null
        val verifier = ColetorPackageVerifier(link.kid, link.publicKeySpki)
        // Revalida o guardado: arquivo mexido no aparelho (ou de outra chave) é como não ter.
        val stored = store.envelope(templateId)?.let { json ->
            runCatching { verifier.verify(json) }.onFailure {
                Logger.event(LogLevel.WARN, "coletor_stored_package_rejected", extra = mapOf("templateId" to templateId, "error" to it.message))
                store.deleteEnvelope(templateId)
            }.getOrNull()
        }
        val check = remote.version(link, templateId)

        val (pkg, versionCheck) = if (stored == null) {
            when (check) {
                is VersionCheck.Answered -> download(link, verifier, templateId, check.packageSha256) to VersionCheckResult.CONFIRMED
                // Nunca baixado e o PC não confirma nada: para a ponte, o template não existe aqui.
                VersionCheck.NotPublished, VersionCheck.Unreachable -> return null
                VersionCheck.Unauthorized -> throw TemplateVersionUnconfirmedException(templateId, MSG_UNAUTHORIZED)
            }
        } else {
            when (val d = ColetorPrintPolicy.decide(stored.packageSha256, check, store.confirmation(templateId), clock.elapsedRealtimeMs(), clock.bootId())) {
                is Decision.Print -> stored to d.versionCheck
                is Decision.Update -> {
                    val fresh = download(link, verifier, templateId, d.packageSha256)
                    Logger.event(LogLevel.INFO, "coletor_package_updated", extra = mapOf("templateId" to templateId, "from" to stored.pkg.templateVersion, "to" to fresh.pkg.templateVersion))
                    fresh to VersionCheckResult.CONFIRMED
                }
                is Decision.Refuse -> when (d.reason) {
                    RefuseReason.UNPUBLISHED -> {
                        store.deleteEnvelope(templateId)
                        throw UnknownTemplateException(templateId, "Template '$templateId' foi despublicado no PC das etiquetas. Nada foi impresso.")
                    }
                    RefuseReason.UNAUTHORIZED -> throw TemplateVersionUnconfirmedException(templateId, MSG_UNAUTHORIZED)
                    RefuseReason.VERSION_UNCONFIRMED -> throw TemplateVersionUnconfirmedException(templateId, d.message)
                }
            }
        }
        if (versionCheck == VersionCheckResult.CONFIRMED) {
            store.saveConfirmation(templateId, ColetorPrintPolicy.Confirmation(pkg.packageSha256, clock.elapsedRealtimeMs(), clock.bootId()))
        }
        return assemble(templateId, pkg, variables, printer, copies, versionCheck)
    }

    /** Baixa, valida e guarda. Só aceita exatamente o pacote que a conferência anunciou. */
    private fun download(link: ColetorLink, verifier: ColetorPackageVerifier, templateId: String, expectedSha: String): VerifiedPackage {
        val json = when (val e = remote.envelope(link, templateId)) {
            is ColetorRemote.Envelope.Ok -> e.json
            ColetorRemote.Envelope.Unauthorized -> throw TemplateVersionUnconfirmedException(templateId, MSG_UNAUTHORIZED)
            ColetorRemote.Envelope.NotPublished -> throw UnknownTemplateException(templateId, "Template '$templateId' foi despublicado no PC das etiquetas. Nada foi impresso.")
            ColetorRemote.Envelope.Unreachable -> throw TemplateVersionUnconfirmedException(templateId, "Não deu para baixar a versão nova do PC das etiquetas. Nada foi impresso.")
        }
        val verified = try {
            verifier.verify(json)
        } catch (e: ColetorPackageException) {
            Logger.event(LogLevel.WARN, "coletor_package_rejected", extra = mapOf("templateId" to templateId, "reason" to e.reason.name, "error" to e.message))
            throw TemplateVersionUnconfirmedException(templateId, "Pacote do PC recusado (${e.message}). Nada foi impresso.")
        }
        if (verified.packageSha256 != expectedSha || verified.pkg.templateId != templateId) {
            // O PC publicou de novo no meio do caminho (ou respondeu outro template): não imprime às cegas.
            throw TemplateVersionUnconfirmedException(templateId, "A etiqueta mudou no PC durante a atualização. Tente de novo.")
        }
        store.saveEnvelope(templateId, json)
        return verified
    }

    private fun assemble(
        templateId: String,
        verified: VerifiedPackage,
        variables: Map<String, String>,
        printer: Printer,
        copies: Int,
        versionCheck: VersionCheckResult,
    ): DesignerLabel {
        val pkg = verified.pkg
        val target = ColetorPrintPolicy.chooseTarget(pkg, printer.language, printer.model, printer.dpi)
            ?: throw TemplateLanguageMismatchException(
                templateId,
                pkg.targets.joinToString { "${it.language}/${it.dpi}" },
                "${ColetorPrintPolicy.effectiveLanguage(printer.language, printer.model) ?: printer.language}/${printer.dpi} (${ColetorPrintPolicy.missingTargetMessage(pkg, printer.language, printer.model, printer.dpi)})",
            )
        // Todos os campos que faltam de uma vez (o app chamador corrige numa volta só).
        val missing = target.slots.map { it.field }.distinct().filter { variables[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) throw MissingTemplateVariablesException(templateId, missing)
        val bytes = try {
            ColetorAssembler.assemble(target, variables, copies)
        } catch (e: SlotValueException) {
            if (e.code == SlotValueException.Code.MISSING) throw MissingTemplateVariablesException(templateId, listOf(e.field))
            throw InvalidTemplateValueException(templateId, e.field, e.message ?: "valor inválido")
        }
        val tspl = target.language == PrinterLanguage.TSPL
        return DesignerLabel(
            payload = String(bytes, if (tspl) Charsets.ISO_8859_1 else Charsets.UTF_8),
            type = if (tspl) PrintPayloadType.TSPL else PrintPayloadType.ZPL,
            documentName = "${pkg.name} v${pkg.templateVersion}",
            templateVersion = pkg.templateVersion,
            packageSha256 = verified.packageSha256,
            versionCheck = versionCheck,
            labelSha256 = ColetorPackageVerifier.sha256Hex(bytes),
            target = "${target.language}/${target.dpi}",
        )
    }

    companion object {
        /** Mesmo critério do print-server para templateId (vira nome de arquivo nos dois lados). */
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        const val MSG_UNAUTHORIZED = "Coletor não autorizado no PC das etiquetas (revogado?). Nada foi impresso."
    }
}
