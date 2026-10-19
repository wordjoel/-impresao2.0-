package br.com.atos.printbridge.domain.validation

/**
 * Aviso estrutural de ZPL detectado na entrada de um job — nunca uma rejeição.
 *
 * O ZPL é um protocolo com muitos dialetos (TSC/Zebra/SATO divergem em gens e tolerâncias), então
 * uma validação que RECUSA a imprimir seria um bloqueio perigoso: um payload ligeiramente fora do
 * espectro de heurística mas aceito pela TSC real deixaria de imprimir. Por isso a validação desta
 * feature é **não bloqueante por decisão de desenho** (roadmap P1.3): o job entra na fila
 * normalmente, mas carrega estes avisos para o operador ver — na fila e no histórico — antes de
 * confiar no status `SENT` ("bytes entregues à conexão", que não é prova de impressão física).
 */
data class ZplWarning(val code: String, val message: String)

/**
 * Verificação estrutural superficial de ZPL. Cobre os casos que enganam o status `SENT` com mais
 * frequência na prática (documentado em P1.3 do roadmap): ausência de início/fim, blocos
 * desbalanceados e conteúdo além do `^XZ` final. Não valida semântica de cada comando — isso é
 * território do interpretador da própria impressora.
 */
object ZplValidator {

    private val BLOCK_OPEN = Regex("\\^XA")
    private val BLOCK_CLOSE = Regex("\\^XZ")

    fun validate(payload: String): List<ZplWarning> {
        val warnings = mutableListOf<ZplWarning>()
        val content = payload.trim()
        if (content.isBlank()) {
            // Vazio já é bloqueado pelo EmptyPayloadException no EnqueuePrintJobUseCase — aqui só
            // não geramos avisos sobre um payload que nem chegou a existir de verdade.
            return emptyList()
        }

        if (!content.contains("^XA")) {
            warnings += ZplWarning("MISSING_BLOCK_START", "Não há comando de início ^XA no conteúdo.")
        }

        if (!content.endsWith("^XZ")) {
            warnings += ZplWarning("MISSING_BLOCK_END", "Não há comando de fim ^XZ no final do conteúdo — a impressora pode não processar a etiqueta.")
        }

        val opens = BLOCK_OPEN.findAll(content).count()
        val closes = BLOCK_CLOSE.findAll(content).count()
        if (opens != closes) {
            warnings += ZplWarning(
                "UNBALANCED_BLOCKS",
                "Contagem de ^XA (início) e ^XZ (fim) não confere: $opens início / $closes fim.",
            )
        }

        val lastClose = content.lastIndexOf("^XZ")
        if (lastClose >= 0 && content.substring(lastClose + 3).trim().isNotEmpty()) {
            warnings += ZplWarning(
                "TRAILING_CONTENT_AFTER_END",
                "Há conteúdo após o ^XZ final que a impressora provavelmente não imprime.",
            )
        }

        return warnings
    }
}