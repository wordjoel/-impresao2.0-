package br.com.atos.printbridge.domain.error

class LabelTemplateNotFoundException(id: String) :
    PrintBridgeException("Modelo de etiqueta não encontrado: $id")

/** [detail] é o texto de validação, já seguro para o operador (nunca contém dado interno). */
class InvalidLabelTemplateException(val detail: String) :
    PrintBridgeException("Modelo de etiqueta inválido: $detail")

class NoDefaultLabelTemplateException : PrintBridgeException("Nenhum modelo de etiqueta padrão configurado")
