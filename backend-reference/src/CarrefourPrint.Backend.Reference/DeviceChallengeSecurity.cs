using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// A chave pública que o backend registrou para um `deviceId` na ativação — nunca alterada sem um
/// fluxo explícito de troca de credencial (`CARREFOUR-PRINT-DEVICE-IDENTITY.md` §2.2). Prova de
/// posse contínua (heartbeat) sempre verifica contra ESTA chave, nunca contra uma apresentada de
/// novo pelo cliente na própria chamada — isso é exatamente o que fecha o ataque "copied deviceId":
/// um atacante que conhece/copia um `deviceId` alheio (público por design,
/// `CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md` §1) não tem a chave PRIVADA correspondente, então
/// nunca produz uma assinatura que bata com a chave pública já registrada.
/// </summary>
public sealed record RegisteredDevicePublicKey(string DeviceId, string TenantId, byte[] PublicKeySpki);

public interface IDevicePublicKeyRegistry
{
    void Register(RegisteredDevicePublicKey key);
    RegisteredDevicePublicKey? Find(string deviceId);
}

public sealed class InMemoryDevicePublicKeyRegistry : IDevicePublicKeyRegistry
{
    private readonly ConcurrentDictionary<string, RegisteredDevicePublicKey> _keys = new();
    public void Register(RegisteredDevicePublicKey key) => _keys[key.DeviceId] = key;
    public RegisteredDevicePublicKey? Find(string deviceId) => _keys.GetValueOrDefault(deviceId);
}

public sealed record DeviceChallenge
{
    public required string Nonce { get; init; }
    public required string DeviceId { get; init; }
    public required DateTimeOffset ExpiresAtUtc { get; init; }
    public bool Consumed { get; init; }
}

/// <summary>
/// Todo resultado NÃO-`Verified` é uma forma de fail-closed — nenhum deles autoriza nada.
/// Deliberadamente **um único código para "nonce não existe" e "nonce existe mas é de outro
/// device"** (<see cref="ChallengeNotFound"/>) — mesma razão anti-enumeração de 404-nunca-403
/// já estabelecida para recursos entre tenants (`CARREFOUR-PRINT-TENANT-ISOLATION.md` §4): revelar
/// a diferença deixaria um atacante confirmar que um nonce específico pertence a outro device.
/// </summary>
public enum ChallengeVerificationResult
{
    Verified,
    UnknownDevice,
    ChallengeNotFound,
    ChallengeExpired,
    ChallengeAlreadyConsumed,
    SignatureInvalid,
}

/// <summary>
/// Fecha, com código real, os dois requisitos que `CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md`
/// §4.2/§4.4 registravam como "requisito funcional obrigatório, não implementado": consumo
/// server-side de nonce de uso único (fecha replay) e verificação de assinatura contra a chave
/// pública JÁ REGISTRADA do device, nunca uma apresentada na própria chamada (fecha deviceId
/// copiado — o atacante não tem a chave privada).
/// </summary>
public sealed class DeviceChallengeService(IDevicePublicKeyRegistry registry, TimeProvider clock)
{
    private readonly ConcurrentDictionary<string, DeviceChallenge> _challenges = new();

    public DeviceChallenge IssueChallenge(string deviceId, TimeSpan ttl)
    {
        var nonce = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var challenge = new DeviceChallenge
        {
            Nonce = nonce,
            DeviceId = deviceId,
            ExpiresAtUtc = clock.GetUtcNow() + ttl,
            Consumed = false,
        };
        _challenges[nonce] = challenge;
        return challenge;
    }

    /// <summary>
    /// Consome o nonce na PRIMEIRA tentativa, mesmo que a assinatura acabe sendo inválida —
    /// deliberado: se o consumo só acontecesse em caso de sucesso, um atacante poderia tentar
    /// múltiplas assinaturas diferentes contra o mesmo nonce até acertar (um oráculo de
    /// verificação) — consumir sempre na primeira tentativa fecha essa janela, ao custo de um
    /// dispositivo legítimo com um bug de assinatura precisar de um novo desafio para tentar de
    /// novo (aceitável — desafios são baratos de emitir, `IssueChallenge`).
    /// </summary>
    public ChallengeVerificationResult VerifyResponse(string nonce, string deviceId, byte[] signature)
    {
        while (true)
        {
            if (!_challenges.TryGetValue(nonce, out var current) || current.DeviceId != deviceId)
            {
                return ChallengeVerificationResult.ChallengeNotFound;
            }

            if (current.Consumed)
            {
                return ChallengeVerificationResult.ChallengeAlreadyConsumed;
            }

            if (clock.GetUtcNow() > current.ExpiresAtUtc)
            {
                return ChallengeVerificationResult.ChallengeExpired;
            }

            var updated = current with { Consumed = true };
            if (!_challenges.TryUpdate(nonce, updated, current))
            {
                continue; // outra chamada concorrente mudou o registro entre a leitura e agora — relê e tenta de novo (CAS)
            }

            var registered = registry.Find(deviceId);
            if (registered is null)
            {
                return ChallengeVerificationResult.UnknownDevice;
            }

            var nonceBytes = Convert.FromBase64String(nonce);
            return VerifySignature(registered.PublicKeySpki, nonceBytes, signature)
                ? ChallengeVerificationResult.Verified
                : ChallengeVerificationResult.SignatureInvalid;
        }
    }

    private static bool VerifySignature(byte[] publicKeySpki, byte[] data, byte[] signature)
    {
        using var ecdsa = ECDsa.Create();
        ecdsa.ImportSubjectPublicKeyInfo(publicKeySpki, out _);
        try
        {
            return ecdsa.VerifyData(data, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            // Assinatura em formato inválido (não DER, tamanho errado) é uma assinatura inválida,
            // nunca um erro de sistema — mesmo resultado fail-closed de uma assinatura que
            // simplesmente não confere.
            return false;
        }
    }
}
