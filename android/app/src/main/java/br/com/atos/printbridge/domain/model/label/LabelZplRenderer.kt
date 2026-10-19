package br.com.atos.printbridge.domain.model.label

/**
 * Porta de renderização de etiqueta: converte um [LabelTemplate] + [LabelData] no payload que a
 * impressora entende. A implementação da V1 é ZPL (TSC ML241/MB241) e vive em `printing/label/`.
 *
 * O domínio depende desta interface; o módulo de etiquetas nunca abre socket nem fala com a fila
 * diretamente — quem entrega o payload é o `PrintJob` existente.
 */
interface LabelZplRenderer {
    fun render(template: LabelTemplate, data: LabelData = LabelData.sample()): String
}
