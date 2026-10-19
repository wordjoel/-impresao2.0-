package br.com.atos.printbridge.domain.model.label

/**
 * Conjunto de valores para os campos variáveis de uma impressão. Um campo ausente cai no valor de
 * exemplo do próprio [LabelField] — é o que permite pré-visualizar uma etiqueta sem ter dados
 * reais de produto (mesma ideia do `PESO_EXEMPLO`/`TARA_EXEMPLO` do PesoHub).
 */
data class LabelData(val values: Map<LabelField, String> = emptyMap()) {

    fun resolve(field: LabelField): String = values[field]?.takeIf { it.isNotBlank() } ?: field.sample

    companion object {
        /** Dados só de exemplo, para editores e pré-visualização. */
        fun sample(): LabelData = LabelData(LabelField.entries.associateWith { it.sample })
    }
}
