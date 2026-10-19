using System.Security.Cryptography;
using CarrefourPrint.Bridge.Infrastructure.License;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Testa a identidade de dispositivo Windows de verdade, contra o Key Storage Provider real
/// (CNG) — não é possível/apropriado simular isso com um fake, exatamente pela mesma razão que
/// AndroidKeystoreDeviceIdentityProvider (Android) não tem teste unitário: o comportamento que
/// importa (chave não-exportável em hardware/KSP real) só existe no provider real. Usa um nome
/// de chave isolado de teste, sempre removido do key storage ao final (nunca toca a chave de
/// produção "CarrefourPrintBridgeDeviceIdentity").
/// </summary>
public class WindowsCngDeviceIdentityProviderTests
{
    private static string TestKeyName() => $"CarrefourPrintBridgeTest_{Guid.NewGuid():N}";

    [Fact]
    public void DeviceId_is_stable_across_calls_and_derived_from_public_key()
    {
        var keyName = TestKeyName();
        using var provider = new WindowsCngDeviceIdentityProvider(keyName);
        try
        {
            var id1 = provider.DeviceId();
            var id2 = provider.DeviceId();
            var publicKey = provider.PublicKey();
            var expectedId = Convert.ToHexStringLower(SHA256.HashData(publicKey));

            Assert.Equal(id1, id2);
            Assert.Equal(expectedId, id1);
            Assert.Equal(64, id1.Length); // SHA-256 em hex = 64 caracteres, CARREFOUR-PRINT-CROSS-PLATFORM.md §2
        }
        finally
        {
            provider.DeleteKeyForTesting();
        }
    }

    [Fact]
    public void DeviceId_is_stable_across_separate_provider_instances_pointing_at_the_same_key()
    {
        var keyName = TestKeyName();
        using var provider1 = new WindowsCngDeviceIdentityProvider(keyName);
        var idFromFirstInstance = provider1.DeviceId();

        using var provider2 = new WindowsCngDeviceIdentityProvider(keyName);
        try
        {
            // Uma segunda instância apontando para o mesmo nome de chave deve carregar a MESMA
            // chave já persistida no key storage (não gerar uma nova) — é essa persistência que
            // garante que reabrir o app/serviço não gera um novo deviceId a cada execução.
            Assert.Equal(idFromFirstInstance, provider2.DeviceId());
        }
        finally
        {
            provider2.DeleteKeyForTesting();
        }
    }

    [Fact]
    public void Signature_produced_by_sign_verifies_against_the_exported_public_key()
    {
        var keyName = TestKeyName();
        using var provider = new WindowsCngDeviceIdentityProvider(keyName);
        try
        {
            var challenge = "carrefour-print-heartbeat-nonce-test"u8.ToArray();
            var signature = provider.Sign(challenge);

            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(provider.PublicKey(), out _);
            var valid = verifier.VerifyData(challenge, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

            Assert.True(valid);
            // DER SEQUENCE sempre começa com 0x30 — confirma que NÃO estamos no formato raw
            // P1363 (que seria só r||s, 64 bytes fixos para P-256, sem esse header). Ver
            // CARREFOUR-PRINT-CROSS-PLATFORM.md §2 regra 4.
            Assert.Equal(0x30, signature[0]);
        }
        finally
        {
            provider.DeleteKeyForTesting();
        }
    }
}
