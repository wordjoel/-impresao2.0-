using CarrefourPrint.Bridge.Domain.Abstractions;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Implementa o passo "Device: assina challenge usando sua chave privada" do desenho de
/// challenge-response — CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md §1 e
/// CARREFOUR-PRINT-CROSS-PLATFORM.md §9. Orquestra apenas: decodifica o nonce (Base64, como
/// chega no HeartbeatChallenge.nonce), pede a assinatura a IDeviceIdentityProvider (que nunca
/// expõe a chave privada, só a operação de assinar) e recodifica o resultado para o formato de
/// fio (Base64 DER — HeartbeatVerification.signature).
///
/// Deliberadamente burro/fino: toda a segurança real está em IDeviceIdentityProvider (a chave
/// nunca sai do Keystore/CNG) e no formato de assinatura já fixado em CARREFOUR-PRINT-CROSS-PLATFORM.md
/// §2 regra 4 (DER, não P1363) — este use case não introduz nenhuma decisão criptográfica nova.
/// </summary>
public sealed class RespondToDeviceChallengeUseCase(IDeviceIdentityProvider deviceIdentityProvider)
{
    /// <param name="challengeBase64">O campo `nonce` recebido de POST /devices/{deviceId}/heartbeat/challenge.</param>
    /// <returns>Assinatura ASN.1 DER, Base64 — vai no campo `signature` de HeartbeatVerification.</returns>
    public string Respond(string challengeBase64)
    {
        var challengeBytes = Convert.FromBase64String(challengeBase64);
        var signatureBytes = deviceIdentityProvider.Sign(challengeBytes);
        return Convert.ToBase64String(signatureBytes);
    }
}
