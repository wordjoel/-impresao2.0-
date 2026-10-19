package br.com.atos.printbridge.domain.model

import java.nio.charset.Charset

enum class PrintPayloadType {
    ZPL,

    /** Documento PDF enviado por IPP (`application/pdf`) a impressoras que o interpretam. Binário. */
    PDF,
    RAW,

    /** Páginas em PWG Raster (`image/pwg-raster`) enviadas por IPP. Binário. */
    PWG_RASTER,

    /**
     * Comandos TSPL (TSC). Podem carregar dados BINÁRIOS (ex.: `BITMAP` da rasterização), por isso
     * são guardados na fila como String ISO-8859-1 — o único charset que mapeia 1:1 byte↔char e
     * devolve exatamente os mesmos bytes no envio. Também é o mais próximo do codepage 1252 que as
     * TSC usam por padrão para texto.
     */
    TSPL;

    /** Charset usado para transformar o payload guardado nos bytes que vão ao socket. */
    val wireCharset: Charset
        get() = if (this == TSPL || this == PDF || this == PWG_RASTER) Charsets.ISO_8859_1 else Charsets.UTF_8

    /** MIME do documento no Print-Job IPP; nulo = não é documento IPP (ZPL/TSPL/RAW vão por socket). */
    val ippDocumentFormat: String?
        get() = when (this) {
            PDF -> PrinterCapabilities.PDF
            PWG_RASTER -> PrinterCapabilities.PWG_RASTER
            else -> null
        }
}

enum class PrinterLanguage {
    ZPL,
    TSPL,
    AUTO,
}

/**
 * Como o Print Bridge fala com a impressora. `TCP` é o socket RAW (JetDirect/AppSocket) — o nome
 * fica por compatibilidade com o banco; `IPP` é o Internet Printing Protocol sobre HTTP.
 */
enum class PrinterProtocol(val label: String) {
    TCP("RAW (socket TCP)"),
    IPP("IPP"),
    BLUETOOTH("Bluetooth"),
    USB("USB"),
}

enum class PrinterModel(val label: String) {
    /** Modelo não identificado e não escolhido — nunca se presume um modelo TSC. */
    UNKNOWN("Não informado"),
    TSC_ML241("TSC ML241"),
    TSC_MB241("TSC MB241"),
    OTHER_TSC("Outra TSC"),
    GENERIC_ZPL("Genérica ZPL"),
}
