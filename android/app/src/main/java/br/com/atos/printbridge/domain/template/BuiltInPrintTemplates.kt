package br.com.atos.printbridge.domain.template

import br.com.atos.printbridge.domain.model.PrinterLanguage

/**
 * Templates embarcados no app. Fase 1 entrega só a etiqueta de referência SIF — de propósito:
 * templates de varejo (preço, promoção, transferência...) estão fora de escopo desta fase.
 */
object BuiltInPrintTemplates {

    const val SIF_PESAGEM_100X72_ID = "sif-pesagem-100x72"

    /**
     * Etiqueta de pesagem com selo sanitário S.I.F., 100x72mm a 203dpi (812x576 dots), conforme a
     * foto da etiqueta de produção (TSC ML241P). Corpo transcrito EXATAMENTE do prompt mestre
     * (seção 7) — qualquer ajuste físico (^POI para a rotação de 180°, ^CI28 para acentuação do
     * "Peso Líquido") é decisão da validação na impressora real, não deste código.
     *
     * O carimbo circular do S.I.F. não entra aqui: é gráfico fixo, a ser gravado na memória da
     * impressora (~DG) e referenciado por ^XG quando for necessário.
     */
    val SIF_PESAGEM_100X72 = PrintTemplate(
        id = SIF_PESAGEM_100X72_ID,
        name = "Pesagem S.I.F. 100x72",
        language = PrinterLanguage.ZPL,
        body = """
            ^XA
            ^MMT
            ^PW812
            ^LL576
            ^LS0
            ^FO30,30^BY2,2,60
            ^BCN,60,Y,N,N
            ^FD{{codigoBarra1}}^FS
            ^FO30,110^BY2,2,60
            ^BCN,60,Y,N,N
            ^FD{{codigoBarra2}}^FS
            ^FO30,190^BY2,2,60
            ^BCN,60,Y,N,N
            ^FD{{codigoBarra3}}^FS
            ^FO30,270^BY2,2,60
            ^BCN,60,Y,N,N
            ^FD{{codigoBarra4}}^FS
            ^FO560,30^BQN,2,6
            ^FDQA,{{qrCodePayload}}^FS
            ^FO400,200^GB60,60,3^FS
            ^FO480,190^A0N,28,28^FDPeso Líquido^FS
            ^FO480,225^A0N,36,36^FD{{pesoLiquido}}^FS
            ^FO480,270^A0N,28,28^FDPeso Bruto^FS
            ^FO480,305^A0N,36,36^FD{{pesoBruto}}^FS
            ^FO680,500^A0N,24,24^FDSequ. {{loteSequencia}}^FS
            ^XZ
        """.trimIndent(),
    )

    val all: List<PrintTemplate> = listOf(SIF_PESAGEM_100X72)

    fun byId(id: String): PrintTemplate? = all.firstOrNull { it.id == id }
}
