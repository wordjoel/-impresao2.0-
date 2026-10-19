using System.Security.Cryptography;

namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Fecha, com criptografia real (não simulada), duas das treze cenas pedidas nesta revisão:
/// "copied deviceId" e "replay". Fecha também, como efeito colateral necessário do mesmo
/// mecanismo, "invalid signature" no contexto de challenge-response (distinto do "invalid
/// signature" de License Token, já coberto em Android/Windows `LicenseUseCasesTest`).
/// </summary>
public class DeviceChallengeSecurityTests
{
    private static (byte[] PublicSpki, ECDsa PrivateKey) GenerateDeviceKeyPair()
    {
        var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return (ecdsa.ExportSubjectPublicKeyInfo(), ecdsa);
    }

    private static byte[] Sign(ECDsa privateKey, string base64Nonce) =>
        privateKey.SignData(Convert.FromBase64String(base64Nonce), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    [Fact]
    public void Legitimate_device_with_the_correct_private_key_verifies()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, privateKey) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));

        var result = service.VerifyResponse(challenge.Nonce, "device-1", Sign(privateKey, challenge.Nonce));

        Assert.Equal(ChallengeVerificationResult.Verified, result);
    }

    [Fact]
    public void Copied_device_id_without_the_private_key_fails_signature_verification()
    {
        // O atacante conhece/copiou "device-1" (deviceId é público por design,
        // CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md §1) mas não tem a chave privada
        // correspondente — assina com a SUA PRÓPRIA chave, gerada agora, nunca registrada.
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (legitimatePublicSpki, _) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", legitimatePublicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));

        var (_, attackerPrivateKey) = GenerateDeviceKeyPair();
        var forgedSignature = Sign(attackerPrivateKey, challenge.Nonce);

        var result = service.VerifyResponse(challenge.Nonce, "device-1", forgedSignature);

        Assert.Equal(ChallengeVerificationResult.SignatureInvalid, result);
    }

    [Fact]
    public void Unregistered_device_id_is_rejected_as_unknown()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var (_, someKey) = GenerateDeviceKeyPair();
        var challenge = service.IssueChallenge("device-never-registered", TimeSpan.FromMinutes(5));

        var result = service.VerifyResponse(challenge.Nonce, "device-never-registered", Sign(someKey, challenge.Nonce));

        Assert.Equal(ChallengeVerificationResult.UnknownDevice, result);
    }

    [Fact]
    public void Replaying_the_same_valid_response_a_second_time_fails()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, privateKey) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));
        var signature = Sign(privateKey, challenge.Nonce);
        Assert.Equal(ChallengeVerificationResult.Verified, service.VerifyResponse(challenge.Nonce, "device-1", signature));

        var replay = service.VerifyResponse(challenge.Nonce, "device-1", signature);

        Assert.Equal(ChallengeVerificationResult.ChallengeAlreadyConsumed, replay);
    }

    [Fact]
    public void Expired_challenge_is_rejected_even_with_a_valid_signature()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, privateKey) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var fakeClock = new FakeTimeProvider(DateTimeOffset.UtcNow);
        var service = new DeviceChallengeService(registry, fakeClock);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));
        fakeClock.Advance(TimeSpan.FromMinutes(6));

        var result = service.VerifyResponse(challenge.Nonce, "device-1", Sign(privateKey, challenge.Nonce));

        Assert.Equal(ChallengeVerificationResult.ChallengeExpired, result);
    }

    [Fact]
    public void Signature_over_different_data_never_verifies_against_this_challenge()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, privateKey) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));
        var signatureOverSomethingElse = Sign(privateKey, Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));

        var result = service.VerifyResponse(challenge.Nonce, "device-1", signatureOverSomethingElse);

        Assert.Equal(ChallengeVerificationResult.SignatureInvalid, result);
    }

    [Fact]
    public void Malformed_signature_bytes_never_crash_and_never_verify()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, _) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));

        var result = service.VerifyResponse(challenge.Nonce, "device-1", [0x01, 0x02, 0x03]);

        Assert.Equal(ChallengeVerificationResult.SignatureInvalid, result);
    }

    [Fact]
    public async Task Concurrent_attempts_to_use_the_same_nonce_succeed_at_most_once()
    {
        var registry = new InMemoryDevicePublicKeyRegistry();
        var (publicSpki, privateKey) = GenerateDeviceKeyPair();
        registry.Register(new RegisteredDevicePublicKey("device-1", "tenant-a", publicSpki));
        var service = new DeviceChallengeService(registry, TimeProvider.System);
        var challenge = service.IssueChallenge("device-1", TimeSpan.FromMinutes(5));
        var signature = Sign(privateKey, challenge.Nonce);

        var results = await Task.WhenAll(Enumerable.Range(0, 32)
            .Select(_ => Task.Run(() => service.VerifyResponse(challenge.Nonce, "device-1", signature))));

        Assert.Single(results, r => r == ChallengeVerificationResult.Verified);
        Assert.All(results.Where(r => r != ChallengeVerificationResult.Verified), r => Assert.Equal(ChallengeVerificationResult.ChallengeAlreadyConsumed, r));
    }

    private sealed class FakeTimeProvider(DateTimeOffset start) : TimeProvider
    {
        private DateTimeOffset _now = start;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }
}
