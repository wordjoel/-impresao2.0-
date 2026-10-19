package br.com.atos.printbridge.domain.model.label

/**
 * Campos de **dado variável** de uma etiqueta (Fase 13) — o que muda de uma impressão para outra.
 *
 * O PesoHub modelava cada um destes como um `ElementoTipo` próprio (`nome`, `preco`, `peso`,
 * `tara`, `validade`, `dataEmbalagem`, `lote`, `textoExtra5`, `textoExtra7`), porque lá existe
 * cadastro de produto e uma balança de origem. O Atos Print Bridge é um app de impressão **sem
 * cadastro de produto** e **sem balança**: a mesma semântica é preservada como um único tipo de
 * elemento (`LabelElementType.CAMPO`) parametrizado por este enum. Ver
 * PESOHUB-LABEL-MODULE-AUDIT.md §3.
 */
enum class LabelField(val label: String, val sample: String) {
    PRODUTO("Produto", "PRODUTO EXEMPLO"),
    PRECO("Preço", "12,99"),
    PRECO_KG("Preço por kg", "R$ 12,99/kg"),
    PESO("Peso", "0,190 kg"),
    TARA("Tara", "0,000 kg"),
    VALIDADE("Validade", "31-12-26"),
    DATA("Data", "17-09-26"),
    LOTE("Lote", "L-0001"),
    CODIGO("Código", "7891000100103"),
    LOJA("Loja", "Loja 4521"),
    OPERADOR("Operador", "OP-01"),
    TEXTO_EXTRA("Texto extra", "—"),
}
