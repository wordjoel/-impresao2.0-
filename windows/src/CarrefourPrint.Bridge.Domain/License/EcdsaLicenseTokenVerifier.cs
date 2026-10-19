using System.Security.Cryptography;
using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.License;

/// <summary>
/// Implementação de referência de ILicenseTokenVerifier — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
/// Usa apenas System.Security.Cryptography (BCL, sem API específica de Windows) porque verificar
/// uma assinatura com uma chave PÚBLICA não precisa de hardware seguro — só o material privado
/// (do lado do servidor, nunca deste app) precisa de proteção especial. Por isso esta classe vive
/// no Domain (portável, testável em qualquer SO), diferente de WindowsCngDeviceIdentityProvider
/// (que sim precisa de CNG, porque ali é a chave PRIVADA do dispositivo que precisa de proteção).
///
/// Formato do token: "{payloadBase64Url}.{assinaturaBase64Url}" — payload é a string canônica
/// "deviceId|licenseId|tenantId|status|issuedAt|validUntil|maxOfflineMillis" em UTF-8, assinada
/// com ECDSA P-256/SHA-256 em formato DER (mesmo formato usado em toda a malha de identidade de
/// dispositivo, CARREFOUR-PRINT-CROSS-PLATFORM.md §2 regra 4 — um único formato de assinatura em
/// todo o sistema, não dois).
/// </summary>
public sealed class EcdsaLicenseTokenVerifier(byte[] serverPublicKeySpki) : ILicenseTokenVerifier
{
    public bool TryVerify(string compactToken, out LicenseTokenPayload? payload)
    {
        payload = null;

        var parts = compactToken.Split('.');
        if (parts.Length != 2) return false;

        byte[] payloadBytes;
        byte[] signatureBytes;
        try
        {
            payloadBytes = Base64UrlDecode(parts[0]);
            signatureBytes = Base64UrlDecode(parts[1]);
        }
        catch (FormatException)
        {
            return false;
        }

        using var ecdsa = ECDsa.Create();
        try
        {
            ecdsa.ImportSubjectPublicKeyInfo(serverPublicKeySpki, out _);
        }
        catch (CryptographicException)
        {
            return false;
        }

        if (!ecdsa.VerifyData(payloadBytes, signatureBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence))
        {
            return false;
        }

        return TryParsePayload(payloadBytes, out payload);
    }

    private static bool TryParsePayload(byte[] payloadBytes, out LicenseTokenPayload? payload)
    {
        payload = null;
        string text;
        try
        {
            text = Encoding.UTF8.GetString(payloadBytes);
        }
        catch (DecoderFallbackException)
        {
            return false;
        }

        var fields = text.Split('|');
        if (fields.Length != 7) return false;
        if (!Enum.TryParse<LicenseTokenStatus>(fields[3], ignoreCase: true, out var status)) return false;
        if (!long.TryParse(fields[4], out var issuedAt)) return false;
        if (!long.TryParse(fields[5], out var validUntil)) return false;
        if (!long.TryParse(fields[6], out var maxOfflineMillis)) return false;

        payload = new LicenseTokenPayload
        {
            DeviceId = fields[0],
            LicenseId = fields[1],
            TenantId = fields[2],
            Status = status,
            IssuedAt = issuedAt,
            ValidUntil = validUntil,
            MaxOfflineMillis = maxOfflineMillis,
        };
        return true;
    }

    private static byte[] Base64UrlDecode(string input)
    {
        var padded = input.Replace('-', '+').Replace('_', '/');
        padded += (padded.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        return Convert.FromBase64String(padded);
    }
}
