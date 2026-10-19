package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.license.LicenseTokenVerifier
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import br.com.atos.printbridge.domain.repository.LicenseRepository
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import javax.inject.Inject

/**
 * Decisão OPERACIONAL local — distinta de [LicenseTokenStatus] (a verdade do backend, 5 estados,
 * CARREFOUR-PRINT-SERVER-LICENSING.md §3). Esta é a simplificação de "posso imprimir agora?"
 * derivada de um LicenseTokenPayload verificado: só PENDING/SUSPENDED/EXPIRED/REVOKED colapsam
 * para RESTRICTED — a distinção fina entre eles importa para o Console/suporte (por isso existe
 * no token), não para a decisão binária de operar ou não localmente.
 */
enum class EffectiveLicenseStatus {
    NOT_ACTIVATED,
    OPERATING,
    RESTRICTED,

    /**
     * Token autêntico, mas emitido para OUTRO aparelho (o `deviceId` assinado não é o deste
     * coletor): token copiado/clonado. Não opera, igual a RESTRICTED, mas com motivo próprio para
     * a tela e o suporte. O registro do CLONE_ATTEMPT é do backend, na ativação.
     */
    WRONG_DEVICE,
}

/**
 * Reescrita para o modelo de License Token assinado (CARREFOUR-PRINT-SERVER-LICENSING.md §5) —
 * substitui a versão anterior, que confiava em um campo `status`/`lastValidatedAt` de leitura
 * livre no estado local (editável por qualquer um com acesso ao DataStore, sem nenhuma
 * verificação criptográfica). Agora: (1) o único dado persistido é o token bruto; (2) a
 * assinatura é sempre verificada contra a chave pública do servidor antes de qualquer decisão;
 * (3) a tolerância offline não é mais uma constante do cliente — é o `validUntil` que o SERVIDOR
 * decidiu ao assinar o token. O backend continua sendo a única autoridade, inclusive sobre por
 * quanto tempo o dispositivo pode operar offline.
 */
class EvaluateLicenseStatusUseCase @Inject constructor(
    private val licenseRepository: LicenseRepository,
    private val tokenVerifier: LicenseTokenVerifier,
    private val deviceIdentity: DeviceIdentityProvider,
) {
    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()): EffectiveLicenseStatus {
        val status = evaluate(licenseRepository.current(), tokenVerifier, nowMillis)
        if (status == EffectiveLicenseStatus.WRONG_DEVICE) {
            Logger.event(LogLevel.WARN, "license_wrong_device")
        }
        return status
    }

    /** Função pura — testável com um verificador real (chave de teste) ou fake, sem repositório. */
    fun evaluate(
        state: LicenseState,
        verifier: LicenseTokenVerifier,
        nowMillis: Long,
        localDeviceId: String = deviceIdentity.deviceId(),
    ): EffectiveLicenseStatus {
        val rawToken = state.rawToken ?: return EffectiveLicenseStatus.NOT_ACTIVATED

        // Falha de verificação (assinatura não confere, token malformado, chave errada) nunca
        // pode ser tratada como "ainda não ativado" — o dispositivo JÁ tem um token, só não é um
        // token confiável. O caminho seguro é sempre RESTRICTED, nunca reabrir a tela de ativação
        // como se nada tivesse acontecido (isso esconderia uma possível adulteração).
        val payload = verifier.verify(rawToken) ?: return EffectiveLicenseStatus.RESTRICTED

        // Anti-clonagem: a assinatura prova que o servidor emitiu o token, não que ele foi emitido
        // para ESTE aparelho. Sem esta checagem, um token válido copiado para outro coletor operava.
        if (payload.deviceId != localDeviceId) return EffectiveLicenseStatus.WRONG_DEVICE

        if (payload.status != LicenseTokenStatus.ACTIVE) return EffectiveLicenseStatus.RESTRICTED
        if (nowMillis > payload.validUntil) return EffectiveLicenseStatus.RESTRICTED
        return EffectiveLicenseStatus.OPERATING
    }
}

/**
 * Substitui RecordLicenseValidationUseCase — em vez de só carimbar um timestamp local, armazena
 * o token NOVO recebido do backend (na ativação ou em cada heartbeat bem-sucedido). O cliente
 * nunca decide por si mesmo que "ainda está válido"; ele só guarda o que o servidor assinou.
 */
class StoreLicenseTokenUseCase @Inject constructor(
    private val licenseRepository: LicenseRepository,
) {
    suspend operator fun invoke(rawToken: String) {
        licenseRepository.update { it.copy(rawToken = rawToken) }
    }
}
