using System.Runtime.Versioning;
using System.Security.Cryptography;
using CarrefourPrint.Bridge.Domain.Abstractions;

namespace CarrefourPrint.Bridge.Infrastructure.License;

/// <summary>
/// Porta de br.com.atos.printbridge.license.AndroidKeystoreDeviceIdentityProvider (Android) para
/// Windows CNG — CARREFOUR-PRINT-SECURITY-MODEL.md §2.2 e CARREFOUR-PRINT-CROSS-PLATFORM.md §2.
///
/// Tenta primeiro o "Microsoft Platform Crypto Provider" (chave apoiada em TPM, quando o hardware
/// suporta); se indisponível, cai para "Microsoft Software Key Storage Provider" — mais fraco que
/// TPM, mas ainda uma chave não-exportável vinculada à máquina via CNG, nunca um arquivo
/// trivialmente copiável para outro computador. Curva P-256, chave NÃO exportável
/// (CngExportPolicies.None) em ambos os casos.
///
/// deviceId é derivado deterministicamente da chave pública (SHA-256), sem armazenamento
/// próprio — mesma decisão do Android: enquanto a chave existir no key storage, o deviceId é
/// sempre o mesmo.
///
/// Formato de assinatura: DSASignatureFormat.Rfc3279DerSequence garante que o .NET produza
/// ASN.1 DER diretamente (em vez do formato raw/IEEE P1363 que seria o padrão em
/// ECDsaCng.SignData sem esse parâmetro) — isso é o que torna a assinatura compatível byte-a-byte
/// com o que o Android produz nativamente via Signature.getInstance("SHA256withECDSA"). Ver o
/// vetor de teste em CARREFOUR-PRINT-CROSS-PLATFORM.md §15.2.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsCngDeviceIdentityProvider : IDeviceIdentityProvider, IDisposable
{
    public const string ProductionKeyName = "CarrefourPrintBridgeDeviceIdentity";

    private readonly string _keyName;
    private readonly Lazy<CngKey> _key;

    /// <param name="keyName">
    /// Nome da chave no key storage do Windows. Parametrizável para permitir testes isolados
    /// (nome distinto, descartado ao final) sem colidir com a identidade real de produção —
    /// nunca alterar o default fora de teste.
    /// </param>
    public WindowsCngDeviceIdentityProvider(string keyName = ProductionKeyName)
    {
        _keyName = keyName;
        _key = new Lazy<CngKey>(LoadOrCreateKey);
    }

    public string DeviceId() => Convert.ToHexStringLower(SHA256.HashData(PublicKey()));

    public byte[] PublicKey()
    {
        using var ecdsa = new ECDsaCng(_key.Value);
        return ecdsa.ExportSubjectPublicKeyInfo();
    }

    public byte[] Sign(byte[] challenge)
    {
        using var ecdsa = new ECDsaCng(_key.Value);
        return ecdsa.SignData(challenge, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
    }

    /// <summary>Remove a chave do key storage — uso exclusivo de testes, para não deixar artefato no key storage da máquina. Nunca chamado em produção.</summary>
    public void DeleteKeyForTesting()
    {
        if (_key.IsValueCreated) _key.Value.Delete();
    }

    private CngKey LoadOrCreateKey()
    {
        foreach (var provider in new[] { new CngProvider("Microsoft Platform Crypto Provider"), CngProvider.MicrosoftSoftwareKeyStorageProvider })
        {
            if (CngKey.Exists(_keyName, provider))
            {
                return CngKey.Open(_keyName, provider);
            }
        }

        try
        {
            return CreateKey(new CngProvider("Microsoft Platform Crypto Provider"));
        }
        catch (CryptographicException)
        {
            // TPM indisponível/sem "Microsoft Platform Crypto Provider" neste hardware — cai para
            // o provedor de software, ainda não-exportável, ainda vinculado à máquina via CNG.
            return CreateKey(CngProvider.MicrosoftSoftwareKeyStorageProvider);
        }
    }

    private CngKey CreateKey(CngProvider provider)
    {
        var parameters = new CngKeyCreationParameters
        {
            Provider = provider,
            KeyUsage = CngKeyUsages.Signing,
            ExportPolicy = CngExportPolicies.None,
            KeyCreationOptions = CngKeyCreationOptions.None,
        };
        return CngKey.Create(CngAlgorithm.ECDsaP256, _keyName, parameters);
    }

    public void Dispose()
    {
        if (_key.IsValueCreated) _key.Value.Dispose();
    }
}
