namespace CarrefourPrint.Bridge.Domain.Errors;

/// <summary>Espelha br.com.atos.printbridge.domain.error.DomainErrors.kt (Android) 1:1.</summary>
public abstract class PrintBridgeException(string message, Exception? cause = null) : Exception(message, cause);

public sealed class InvalidPrinterAddressException(string value)
    : PrintBridgeException($"Endereço de impressora inválido: {value}");

public sealed class InvalidPortException(int value)
    : PrintBridgeException($"Porta inválida: {value} (deve estar entre 1 e 65535)");

public sealed class PrinterNotFoundException(string id)
    : PrintBridgeException($"Impressora não encontrada: {id}");

public sealed class PrintJobNotFoundException(string id)
    : PrintBridgeException($"Trabalho de impressão não encontrado: {id}");

public sealed class EmptyPayloadException() : PrintBridgeException("O conteúdo de impressão está vazio");

public sealed class PayloadTooLargeException(int sizeBytes, int limitBytes)
    : PrintBridgeException($"Conteúdo de impressão excede o limite ({sizeBytes} bytes > {limitBytes} bytes)");

public sealed class NoDefaultPrinterException() : PrintBridgeException("Nenhuma impressora padrão configurada");

/// <summary>
/// Espelha br.com.atos.printbridge.domain.error.LicenseRestrictedException (Android) —
/// CARREFOUR-PRINT-SERVER-LICENSING.md §10. Lançada só ao CRIAR trabalho novo (enqueue/reprint)
/// fora de Operating; nunca pelo motor de fila/retry/recuperação de órfãos.
/// </summary>
public sealed class LicenseRestrictedException(string effectiveStatus)
    : PrintBridgeException($"Licença não está operando ({effectiveStatus}) — novos trabalhos de impressão não podem ser criados")
{
    public string EffectiveStatus { get; } = effectiveStatus;
}
