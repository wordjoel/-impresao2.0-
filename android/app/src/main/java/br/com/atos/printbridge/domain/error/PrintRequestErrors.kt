package br.com.atos.printbridge.domain.error

// Erros da Fase 1 (template {{var}}, rasterização de documento, perfil de mídia). Ficam neste
// pacote porque PrintBridgeException é sealed — e assim o ErrorTranslator continua exaustivo.

class UnknownTemplateException(val templateId: String, detail: String? = null) :
    PrintBridgeException(detail ?: "Template '$templateId' não existe")

/**
 * Template do designer (passo 6) cuja versão não deu para confirmar com o PC das etiquetas: sem
 * resposta fora da janela offline de 15 min, coletor revogado, ou pacote novo que não validou.
 * Nada é impresso — nunca uma versão antiga sem avisar.
 */
class TemplateVersionUnconfirmedException(val templateId: String, detail: String) :
    PrintBridgeException(detail)

/** Faltou variável exigida pelo template — nunca imprimimos com o campo em branco silenciosamente. */
class MissingTemplateVariablesException(val templateId: String, val missing: List<String>) :
    PrintBridgeException("Template '$templateId' exige variáveis não informadas: ${missing.joinToString()}")

/**
 * Valor que, colado no ZPL/TSPL, viraria COMANDO em vez de dado (ex.: um peso "10^XZ" encerraria a
 * etiqueta no meio). Recusar é a única opção segura: escapar exigiria que todo template usasse
 * ^FH/TSPL-escape, e um template que esquecesse isso imprimiria lixo sem aviso.
 */
class InvalidTemplateValueException(val templateId: String, val variable: String, val reason: String) :
    PrintBridgeException("Valor inválido para '$variable' no template '$templateId': $reason")

/** Template ZPL mandado para impressora TSPL (ou vice-versa) — a impressora imprimiria o código como texto. */
class TemplateLanguageMismatchException(val templateId: String, val templateLanguage: String, val printerLanguage: String) :
    PrintBridgeException("Template '$templateId' é $templateLanguage, mas a impressora está configurada como $printerLanguage")

class DocumentRasterizationException(val detail: String, cause: Throwable? = null) :
    PrintBridgeException("Não foi possível converter o documento: $detail", cause)

class InvalidMediaProfileException(val detail: String) :
    PrintBridgeException("Perfil de mídia inválido: $detail")

/**
 * A impressora real não permite o que foi pedido, ou não informou o necessário (formato de
 * documento, papel, linguagem RAW, serviço confirmado). [detail] traz a causa técnica.
 */
class PrinterCapabilityException(val detail: String) :
    PrintBridgeException("Impressora incompatível: $detail")
