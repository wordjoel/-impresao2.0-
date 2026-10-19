using System.Security.Cryptography;
using System.Text;

namespace CarrefourPrint.Bridge.Infrastructure.Tests;

/// <summary>
/// Valida o vetor de teste criptográfico publicado em CARREFOUR-PRINT-CROSS-PLATFORM.md §15.2
/// (gerado e verificado com OpenSSL para aquele documento) usando a API real do .NET
/// (System.Security.Cryptography), a mesma usada por WindowsCngDeviceIdentityProvider — não um
/// key storage de teste, mas a MESMA curva/algoritmo/formato de assinatura (P-256, SHA-256,
/// DSASignatureFormat.Rfc3279DerSequence).
///
/// Não comparamos bytes de assinatura diretamente (ECDSA tem componente aleatório por padrão —
/// duas assinaturas válidas do mesmo dado com a mesma chave são diferentes, mas ambas válidas).
/// O que este teste prova, e que é a propriedade que realmente importa para o contrato
/// cross-platform: (1) uma assinatura DER produzida fora do .NET (aqui, por OpenSSL/Android
/// nativamente) verifica corretamente com a API .NET usando o mesmo formato; (2) uma assinatura
/// produzida pelo próprio .NET com DSASignatureFormat.Rfc3279DerSequence também verifica —
/// ou seja, o formato de fio é o mesmo dos dois lados.
/// </summary>
public class CrossPlatformSignatureVectorTests
{
    // Vetor de CARREFOUR-PRINT-CROSS-PLATFORM.md §15.2 — chave de teste, não é segredo de produção.
    private const string TestPrivateKeyPem = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIBmljNLwcQhaomJSxi+qsMWhuMcCN5a3z0YQyOdS2GGpoAoGCCqGSM49
        AwEHoUQDQgAE+h8+uSCB7+oq3jykAc7kRHcoIM0ok71SiqUfBBJOXEIJcqHHsiuW
        cQQ2P0P4jRtwjWxJvtxyiqU+rN65uwQJLw==
        -----END EC PRIVATE KEY-----
        """;

    private const string TestPublicKeyBase64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+h8+uSCB7+oq3jykAc7kRHcoIM0ok71SiqUf" +
        "BBJOXEIJcqHHsiuWcQQ2P0P4jRtwjWxJvtxyiqU+rN65uwQJLw==";

    private const string ChallengeText = "carrefour-print-heartbeat-nonce-0001";

    private const string ExpectedDeviceIdHex = "8e43784bf8a992e8007234e4e1cdda20359c19dec2d83ae247a950c0a07e9223";

    // Assinatura DER (SHA256withECDSA) publicada em CARREFOUR-PRINT-CROSS-PLATFORM.md §15.2,
    // produzida de forma independente via OpenSSL — não pelo .NET. 144 caracteres hex = 72 bytes.
    private const string OpenSslDerSignatureHex =
        "3046022100ac2dfc28be640a80aa0a4a438c24007a16c810f1424cb4e2d4c6875b5d856ee2" +
        "0221" + "0080b011dec959154d79d2e50ad6a2ef1aacb33f8f19731deac91cbc712a1974bb";

    [Fact]
    public void Published_device_id_matches_sha256_of_the_published_public_key()
    {
        var publicKeyDer = Convert.FromBase64String(TestPublicKeyBase64);
        var deviceId = Convert.ToHexStringLower(SHA256.HashData(publicKeyDer));

        Assert.Equal(ExpectedDeviceIdHex, deviceId);
    }

    [Fact]
    public void Net_verifies_a_der_signature_produced_externally_by_openssl()
    {
        var signature = Convert.FromHexString(OpenSslDerSignatureHex);
        var challenge = Encoding.ASCII.GetBytes(ChallengeText);

        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(TestPublicKeyBase64), out _);

        var valid = verifier.VerifyData(challenge, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        Assert.True(valid);
    }

    [Fact]
    public void Net_produced_signature_is_der_and_verifies_against_the_same_published_public_key()
    {
        using var signer = ECDsa.Create();
        signer.ImportFromPem(TestPrivateKeyPem);

        var challenge = Encoding.ASCII.GetBytes(ChallengeText);
        var signature = signer.SignData(challenge, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        // Confirma formato DER (SEQUENCE) — não o raw P1363 que seria o default de
        // ECDsaCng.SignData sem o parâmetro DSASignatureFormat explícito (CARREFOUR-PRINT-CROSS-PLATFORM.md §2 regra 4).
        Assert.Equal(0x30, signature[0]);

        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(TestPublicKeyBase64), out _);
        var valid = verifier.VerifyData(challenge, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        Assert.True(valid);
    }

    [Fact]
    public void Net_exported_public_key_from_the_test_private_key_matches_the_published_value()
    {
        using var signer = ECDsa.Create();
        signer.ImportFromPem(TestPrivateKeyPem);

        var exported = signer.ExportSubjectPublicKeyInfo();

        Assert.Equal(Convert.FromBase64String(TestPublicKeyBase64), exported);
    }
}
