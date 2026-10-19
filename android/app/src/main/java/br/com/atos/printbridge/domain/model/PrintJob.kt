package br.com.atos.printbridge.domain.model

data class PrintJob(
    val id: String,
    val printerId: String,
    val type: PrintPayloadType,
    val payload: String,
    val documentName: String,
    val copies: Int,
    val status: PrintJobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val errorMessage: String?,
    val externalDocumentId: String?,
    val originalJobId: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val sentAt: Long?,
    val updatedAt: Long,
    val validationWarnings: List<String> = emptyList(),
    /** Documento multipágina (Fase 1): todas as páginas compartilham o groupId; nulo = job avulso. */
    val groupId: String? = null,
    /** Posição da página no documento, a partir de 0. */
    val pageIndex: Int? = null,
    val pageCount: Int? = null,
    /** Retrato da impressora no envio (v8): nome, `ip:porta` e protocolo reais, para o Histórico. */
    val printerName: String? = null,
    val printerAddress: String? = null,
    val printerProtocol: String? = null,
    /** Origem (v9): loja da impressora, coletor (Central de Tokens), aparelho (Keystore), app que pediu, operador. */
    val storeId: String? = null,
    val collectorId: String? = null,
    val deviceId: String? = null,
    val sourceApp: String? = null,
    val operatorName: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val MAX_PAYLOAD_SIZE_BYTES = 512 * 1024

        /**
         * Documento IPP (PDF/PWG Raster) é binário guardado como ISO-8859-1; no SQLite vira UTF-8
         * (até 2 bytes por byte) e o CursorWindow do Android lê no máximo ~2 MB por linha.
         */
        const val MAX_IPP_DOCUMENT_SIZE_BYTES = 900 * 1024

        fun maxPayloadBytesFor(type: PrintPayloadType): Int =
            if (type.ippDocumentFormat != null) MAX_IPP_DOCUMENT_SIZE_BYTES else MAX_PAYLOAD_SIZE_BYTES

        /**
         * Sem limite superior, `copies` vindo de um Intent externo (EXTRA_COPIES,
         * PrintBridgeReceiverActivity) poderia forçar `payload.repeat(copies)` (PrintEngine) a
         * alocar uma string gigantesca — um vetor de negação de serviço local via um Intent mal
         * formado. Achado durante o hardening do release (CARREFOUR-PRINT-ANDROID-RELEASE-HARDENING.md §4).
         */
        const val MAX_COPIES = 100
    }
}
