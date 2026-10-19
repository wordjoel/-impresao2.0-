package br.com.atos.printbridge.domain.template

import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.model.PrinterLanguage

/**
 * Motor de substituição `{{campo}}` → valor. Puro (sem Android), determinístico: a mesma entrada
 * gera sempre o mesmo payload, byte a byte.
 *
 * Regras:
 * - toda variável presente no corpo precisa estar no mapa (valor vazio "" é aceito: é o chamador
 *   dizendo explicitamente "sem dado", diferente de esquecer o campo);
 * - variáveis extras no mapa são ignoradas (o chamador pode mandar um superconjunto);
 * - valores não podem carregar caracteres de controle do protocolo do template.
 */
object PlaceholderTemplateRenderer {

    val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_.\-]+)\s*\}\}""")

    fun render(template: PrintTemplate, variables: Map<String, String>): String {
        val missing = template.variables.filterNot { variables.containsKey(it) }
        if (missing.isNotEmpty()) throw MissingTemplateVariablesException(template.id, missing)

        template.variables.forEach { name -> validate(template, name, variables.getValue(name)) }

        return PLACEHOLDER.replace(template.body) { match -> variables.getValue(match.groupValues[1]) }
    }

    private fun validate(template: PrintTemplate, name: String, value: String) {
        if (value.any { it.isISOControl() }) {
            throw InvalidTemplateValueException(template.id, name, "contém quebra de linha ou caractere de controle")
        }
        val forbidden = when (template.language) {
            // ^ e ~ são prefixos de comando ZPL em qualquer posição do fluxo.
            PrinterLanguage.ZPL -> setOf('^', '~')
            // Em TSPL os dados vão entre aspas: uma aspa fecharia o campo e o resto viraria comando.
            PrinterLanguage.TSPL -> setOf('"')
            PrinterLanguage.AUTO -> emptySet()
        }
        value.firstOrNull { it in forbidden }?.let { ch ->
            throw InvalidTemplateValueException(template.id, name, "o caractere '$ch' é comando de ${template.language.name}")
        }
    }
}
