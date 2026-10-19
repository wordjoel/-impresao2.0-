using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Decisão OPERACIONAL local — distinta de LicenseTokenStatus (a verdade do backend, 5 estados,
/// CARREFOUR-PRINT-SERVER-LICENSING.md §3). Esta é a simplificação de "posso imprimir agora?"
/// derivada de um LicenseTokenPayload verificado: só PENDING/SUSPENDED/EXPIRED/REVOKED colapsam
/// para Restricted — a distinção fina entre eles importa para o Console/suporte (por isso existe
/// no token), não para a decisão binária de operar ou não localmente.
/// </summary>
public enum EffectiveLicenseStatus
{
    NotActivated,
    Operating,
    Restricted,
}

/// <summary>
/// Reescrita de br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase para o
/// modelo de License Token assinado (CARREFOUR-PRINT-SERVER-LICENSING.md §5) — substitui a versão
/// anterior, que confiava em um campo `status`/`lastValidatedAt` de leitura livre no estado local
/// (editável por qualquer um com acesso ao arquivo/DataStore, sem nenhuma verificação
/// criptográfica). Agora: (1) o único dado persistido é o token bruto; (2) a assinatura é sempre
/// verificada contra a chave pública do servidor antes de qualquer decisão; (3) a tolerância
/// offline não é mais uma constante do cliente — é o `validUntil` que o SERVIDOR decidiu ao
/// assinar o token. O backend continua sendo a única autoridade, inclusive sobre por quanto tempo
/// o dispositivo pode operar offline.
/// </summary>
public sealed class EvaluateLicenseStatusUseCase(ILicenseRepository licenseRepository, ILicenseTokenVerifier tokenVerifier)
{
    public async Task<EffectiveLicenseStatus> InvokeAsync(long? nowMillis = null, CancellationToken ct = default)
    {
        var now = nowMillis ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var state = await licenseRepository.CurrentAsync(ct);
        return Evaluate(state, tokenVerifier, now);
    }

    /// <summary>Função pura — testável com um verificador real (chave de teste) ou fake, sem repositório.</summary>
    public static EffectiveLicenseStatus Evaluate(LicenseState state, ILicenseTokenVerifier tokenVerifier, long nowMillis)
    {
        if (state.RawToken is null) return EffectiveLicenseStatus.NotActivated;

        // Falha de verificação (assinatura não confere, token malformado, chave errada) nunca
        // pode ser tratada como "ainda não ativado" — o dispositivo JÁ tem um token, só não é
        // um token confiável. O caminho seguro é sempre Restricted, nunca reabrir a tela de
        // ativação como se nada tivesse acontecido (isso esconderia uma possível adulteração).
        if (!tokenVerifier.TryVerify(state.RawToken, out var payload) || payload is null)
        {
            return EffectiveLicenseStatus.Restricted;
        }

        if (payload.Status != LicenseTokenStatus.Active) return EffectiveLicenseStatus.Restricted;
        if (nowMillis > payload.ValidUntil) return EffectiveLicenseStatus.Restricted;
        return EffectiveLicenseStatus.Operating;
    }
}

/// <summary>
/// Substitui RecordLicenseValidationUseCase — em vez de só carimbar um timestamp local, armazena
/// o token NOVO recebido do backend (na ativação ou em cada heartbeat bem-sucedido). O cliente
/// nunca decide por si mesmo que "ainda está válido"; ele só guarda o que o servidor assinou.
/// </summary>
public sealed class StoreLicenseTokenUseCase(ILicenseRepository licenseRepository)
{
    public Task InvokeAsync(string rawToken, CancellationToken ct = default) =>
        licenseRepository.UpdateAsync(state => state with { RawToken = rawToken }, ct);
}
