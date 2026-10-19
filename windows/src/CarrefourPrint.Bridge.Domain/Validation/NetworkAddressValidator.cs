using System.Text.RegularExpressions;

namespace CarrefourPrint.Bridge.Domain.Validation;

/// <summary>
/// Porta EXATA de br.com.atos.printbridge.domain.validation.NetworkAddressValidator (Android) —
/// mesmas regex, mesma ordem de checagem, para garantir que os dois clientes aceitam/rejeitam
/// exatamente os mesmos endereços (CARREFOUR-PRINT-CROSS-PLATFORM.md §12).
/// </summary>
public static partial class NetworkAddressValidator
{
    [GeneratedRegex(@"^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")]
    private static partial Regex Ipv4Regex();

    [GeneratedRegex(@"^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\-]{0,61}[A-Za-z0-9])$")]
    private static partial Regex HostnameRegex();

    // Forma "N.N.N.N" (todos os rótulos numéricos) — se cair nesse formato, precisa passar pela
    // validação estrita de IPv4 (0-255 por octeto). Sem essa guarda, HostnameRegex aceitaria
    // endereços como "999.999.1.1" como "hostname válido", mascarando um IP digitado errado.
    [GeneratedRegex(@"^\d{1,3}(\.\d{1,3}){3}$")]
    private static partial Regex DottedNumericShapeRegex();

    public static bool IsValidAddress(string value)
    {
        var trimmed = value.Trim();
        if (trimmed.Length == 0) return false;
        if (DottedNumericShapeRegex().IsMatch(trimmed)) return Ipv4Regex().IsMatch(trimmed);
        return HostnameRegex().IsMatch(trimmed);
    }

    public static bool IsValidPort(int port) => port is >= 1 and <= 65535;
}
