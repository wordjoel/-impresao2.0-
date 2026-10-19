package br.com.atos.printbridge.domain.error

sealed class PrintBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InvalidPrinterAddressException(value: String) :
    PrintBridgeException("Endereço de impressora inválido: $value")

class InvalidPortException(value: Int) :
    PrintBridgeException("Porta inválida: $value (deve estar entre 1 e 65535)")

class PrinterNotFoundException(id: String) :
    PrintBridgeException("Impressora não encontrada: $id")

class PrintJobNotFoundException(id: String) :
    PrintBridgeException("Trabalho de impressão não encontrado: $id")

class EmptyPayloadException : PrintBridgeException("O conteúdo de impressão está vazio")

class PayloadTooLargeException(sizeBytes: Int, limitBytes: Int) :
    PrintBridgeException("Conteúdo de impressão excede o limite ($sizeBytes bytes > $limitBytes bytes)")

class InvalidCopiesException(copies: Int, maxCopies: Int) :
    PrintBridgeException("Número de cópias inválido: $copies (máximo $maxCopies)")

class NoDefaultPrinterException : PrintBridgeException("Nenhuma impressora padrão configurada")

/**
 * Lançada ao tentar CRIAR um novo compromisso de impressão (enqueue/reprint) enquanto a licença
 * não está OPERATING — CARREFOUR-PRINT-SERVER-LICENSING.md §10. Nunca lançada pelo motor de
 * processamento da fila local (worker/retry/recuperação de órfãos) — jobs já persistidos antes da
 * restrição continuam sendo processados normalmente; esta exceção só bloqueia trabalho NOVO.
 */
class LicenseRestrictedException(val effectiveStatus: String) :
    PrintBridgeException("Licença não está operando ($effectiveStatus) — novos trabalhos de impressão não podem ser criados")

class InvalidStoreException(message: String) :
    PrintBridgeException("Loja inválida: $message")

class InvalidDeviceException(message: String) :
    PrintBridgeException("Dispositivo inválido: $message")
