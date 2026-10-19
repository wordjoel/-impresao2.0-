package br.com.atos.printbridge.domain.model

/**
 * SENT significa apenas "os bytes foram entregues com sucesso à conexão de impressão"
 * (o socket TCP aceitou e recebeu todo o payload sem exceção). NÃO significa que a etiqueta
 * foi fisicamente impressa — a TSC não confirma isso de volta nesta V1. Nunca renomeie SENT
 * para PRINTED sem antes implementar um canal de confirmação física real.
 */
enum class PrintJobStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    CANCELLED,

    /**
     * O operador removeu este job da fila (ex.: pediu 5 etiquetas e removeu a 1 e a 2 — a 3, 4 e 5
     * seguem valendo). Não é enviado, mas continua no histórico com o motivo. Diferente de
     * CANCELLED, que é o cancelamento pedido pelo app/API ou pelo sistema de impressão.
     */
    DELETED_BY_OPERATOR;

    val isActive: Boolean
        get() = this == PENDING || this == PROCESSING
}
