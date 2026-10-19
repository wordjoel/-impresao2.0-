using System.Security.Cryptography;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.License;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Prova o passo "servidor verifica usando chave pública registrada" de ponta a ponta contra o
/// provider REAL (CNG/TPM ou software KSP, conforme o hardware) — CARREFOUR-PRINT-DEVICE-AUTH-THREAT-MODEL.md §1.
/// Usa um nome de chave de teste isolado, sempre removido ao final.
/// </summary>
public class RespondToDeviceChallengeUseCaseTests
{
    [Fact]
    public void Response_to_a_server_issued_challenge_verifies_against_the_device_public_key()
    {
        var keyName = $"CarrefourPrintBridgeTest_{Guid.NewGuid():N}";
        using var identityProvider = new WindowsCngDeviceIdentityProvider(keyName);
        try
        {
            var useCase = new RespondToDeviceChallengeUseCase(identityProvider);

            // Simula o que o servidor faria: gerar um nonce aleatório de 32 bytes.
            var serverNonce = RandomNumberGenerator.GetBytes(32);
            var challengeBase64 = Convert.ToBase64String(serverNonce);

            var responseBase64 = useCase.Respond(challengeBase64);
            var signatureBytes = Convert.FromBase64String(responseBase64);

            using var serverSideVerifier = ECDsa.Create();
            serverSideVerifier.ImportSubjectPublicKeyInfo(identityProvider.PublicKey(), out _);
            var valid = serverSideVerifier.VerifyData(serverNonce, signatureBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

            Assert.True(valid);
            Assert.Equal(0x30, signatureBytes[0]); // DER SEQUENCE, nunca raw P1363 — mesma regra do device identity
        }
        finally
        {
            identityProvider.DeleteKeyForTesting();
        }
    }

    [Fact]
    public void A_response_signed_for_one_challenge_does_not_verify_against_a_different_challenge()
    {
        // Prova a base do antirreplay: a assinatura é sobre o nonce específico — reapresentá-la
        // contra um nonce diferente (o que aconteceria numa tentativa de replay) falha.
        var keyName = $"CarrefourPrintBridgeTest_{Guid.NewGuid():N}";
        using var identityProvider = new WindowsCngDeviceIdentityProvider(keyName);
        try
        {
            var useCase = new RespondToDeviceChallengeUseCase(identityProvider);
            var originalNonce = RandomNumberGenerator.GetBytes(32);
            var responseBase64 = useCase.Respond(Convert.ToBase64String(originalNonce));
            var signatureBytes = Convert.FromBase64String(responseBase64);

            var differentNonce = RandomNumberGenerator.GetBytes(32);

            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(identityProvider.PublicKey(), out _);
            var valid = verifier.VerifyData(differentNonce, signatureBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

            Assert.False(valid);
        }
        finally
        {
            identityProvider.DeleteKeyForTesting();
        }
    }
}
