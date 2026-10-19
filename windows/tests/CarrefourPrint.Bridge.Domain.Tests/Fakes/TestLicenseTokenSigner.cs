using System.Security.Cryptography;
using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.License;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.Tests.Fakes;

/// <summary>
/// Assina License Tokens de teste com a MESMA chave privada de teste usada para gerar o vetor de
/// CARREFOUR-PRINT-SERVER-LICENSING.md §6 (par gerado com OpenSSL, chave pública correspondente
/// em ServerTrustedPublicKey.TestServerPublicKeySpkiBase64 — Infrastructure). Existe só em teste;
/// nenhum código de produção tem acesso a uma chave privada de servidor.
/// </summary>
public static class TestLicenseTokenSigner
{
    private const string TestServerPrivateKeyPem = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIMgAPawu4N7s56MBmoCA5fho30izo/HoPKIGVz4BbfhAoAoGCCqGSM49
        AwEHoUQDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/aKBEpivJ
        fg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ==
        -----END EC PRIVATE KEY-----
        """;

    public static string Sign(
        string deviceId = "test-device",
        string licenseId = "lic-test",
        string tenantId = "tenant-test",
        LicenseTokenStatus status = LicenseTokenStatus.Active,
        long issuedAt = 1_000_000,
        long validUntil = 1_259_200_000,
        long maxOfflineMillis = 72L * 60 * 60 * 1000)
    {
        var canonical = $"{deviceId}|{licenseId}|{tenantId}|{status.ToString().ToUpperInvariant()}|{issuedAt}|{validUntil}|{maxOfflineMillis}";
        var payloadBytes = Encoding.UTF8.GetBytes(canonical);

        using var ecdsa = ECDsa.Create();
        ecdsa.ImportFromPem(TestServerPrivateKeyPem);
        var signature = ecdsa.SignData(payloadBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        return $"{Base64UrlEncode(payloadBytes)}.{Base64UrlEncode(signature)}";
    }

    /// <summary>Verificador real (não fake) contra a mesma chave pública de teste — usado quando o teste quer provar comportamento de ponta a ponta (assina + verifica), não só a lógica de EvaluateLicenseStatusUseCase isoladamente.</summary>
    public static ILicenseTokenVerifier CreateVerifier() => new EcdsaLicenseTokenVerifier(TestServerPublicKeySpki);

    private static readonly byte[] TestServerPublicKeySpki = Convert.FromBase64String(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/" +
        "aKBEpivJfg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ==");

    private static string Base64UrlEncode(byte[] bytes) =>
        Convert.ToBase64String(bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=');
}
