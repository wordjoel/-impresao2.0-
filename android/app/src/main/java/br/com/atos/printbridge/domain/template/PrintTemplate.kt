package br.com.atos.printbridge.domain.template

import br.com.atos.printbridge.domain.model.PrinterLanguage

/**
 * Template textual de etiqueta com variáveis `{{campo}}` (Fase 1, Etapa 3). Diferente do editor
 * visual de etiquetas (`LabelTemplate`, elementos posicionados): aqui o layout já é o ZPL/TSPL
 * final escrito à mão, e só os dados variáveis mudam por impressão — é o formato que os apps
 * ATHOS chamadores conhecem (templateId + mapa de variáveis).
 */
data class PrintTemplate(
    val id: String,
    val name: String,
    /** Linguagem do corpo — nunca AUTO: o corpo É ZPL ou É TSPL. */
    val language: PrinterLanguage,
    val body: String,
) {
    init {
        require(language != PrinterLanguage.AUTO) { "Template precisa declarar ZPL ou TSPL, nunca AUTO" }
    }

    /** Variáveis exigidas pelo corpo, na ordem em que aparecem pela primeira vez. */
    val variables: List<String> by lazy {
        PlaceholderTemplateRenderer.PLACEHOLDER.findAll(body).map { it.groupValues[1] }.distinct().toList()
    }
}
