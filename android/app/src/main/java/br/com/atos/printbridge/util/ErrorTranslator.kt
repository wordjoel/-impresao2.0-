package br.com.atos.printbridge.util

import br.com.atos.printbridge.domain.error.DocumentRasterizationException
import br.com.atos.printbridge.domain.error.EmptyPayloadException
import br.com.atos.printbridge.domain.error.InvalidCopiesException
import br.com.atos.printbridge.domain.error.InvalidDeviceException
import br.com.atos.printbridge.domain.error.InvalidPortException
import br.com.atos.printbridge.domain.error.InvalidPrinterAddressException
import br.com.atos.printbridge.domain.error.InvalidLabelTemplateException
import br.com.atos.printbridge.domain.error.InvalidMediaProfileException
import br.com.atos.printbridge.domain.error.PrinterCapabilityException
import br.com.atos.printbridge.domain.error.InvalidStoreException
import br.com.atos.printbridge.domain.error.InvalidTemplateValueException
import br.com.atos.printbridge.domain.error.LabelTemplateNotFoundException
import br.com.atos.printbridge.domain.error.LicenseRestrictedException
import br.com.atos.printbridge.domain.error.MissingTemplateVariablesException
import br.com.atos.printbridge.domain.error.NoDefaultLabelTemplateException
import br.com.atos.printbridge.domain.error.NoDefaultPrinterException
import br.com.atos.printbridge.domain.error.PayloadTooLargeException
import br.com.atos.printbridge.domain.error.PrintBridgeException
import br.com.atos.printbridge.domain.error.PrintJobNotFoundException
import br.com.atos.printbridge.domain.error.PrinterNotFoundException
import br.com.atos.printbridge.domain.error.TemplateLanguageMismatchException
import br.com.atos.printbridge.domain.error.UnknownTemplateException

/**
 * Único ponto do app que decide o que um OPERADOR vê quando algo falha — nenhuma outra tela deve
 * interpolar `throwable.message`/`throwable.toString()`/stack trace diretamente numa UI (isso já
 * aconteceu por engano em `HomeViewModel.printSampleLabel` e no export de diagnóstico de
 * `SettingsScreen`, corrigido nesta revisão — ver `CARREFOUR-PRINT-OPERATOR-UX.md`).
 *
 * **Garantia estrutural, não convenção:** [PrintBridgeException] é `sealed` — o `when` interno
 * desta classe é exaustivo SEM cláusula `else`, então adicionar um novo tipo de exceção de domínio
 * sem ensinar este tradutor a lidar com ele é um **erro de compilação**, nunca um esquecimento que
 * só aparece em produção como uma mensagem genérica ou, pior, uma mensagem crua vazando depois.
 *
 * Para qualquer erro que NÃO seja um [PrintBridgeException] conhecido (exceção inesperada de uma
 * camada inferior — Room, I/O, NPE de um bug real) a mensagem é **sempre** o fallback genérico,
 * nunca `error.message`/`error.toString()` — uma exceção inesperada é exatamente o caso onde a
 * mensagem original tem mais chance de conter algo interno (nome de classe, caminho de arquivo,
 * detalhe de driver) que não deveria aparecer para um operador de loja.
 */
object ErrorTranslator {

    private const val GENERIC_FALLBACK =
        "Não foi possível concluir a operação. Tente novamente ou consulte Diagnóstico."

    /** Mensagem curta, em português, segura para exibição direta ao operador — nunca contém `error.message`/stack trace para um erro não reconhecido. */
    fun translate(error: Throwable): String =
        if (error is PrintBridgeException) translateKnown(error) else GENERIC_FALLBACK

    private fun translateKnown(error: PrintBridgeException): String = when (error) {
        is EmptyPayloadException ->
            "O conteúdo de impressão está vazio."
        is PayloadTooLargeException ->
            "O conteúdo de impressão é grande demais para ser enviado à impressora."
        is InvalidCopiesException ->
            "Número de cópias inválido para esta impressão."
        is NoDefaultPrinterException ->
            "Nenhuma impressora padrão configurada. Cadastre uma em Impressoras."
        is PrinterNotFoundException ->
            "Impressora não encontrada. Verifique o cadastro em Impressoras."
        is PrintJobNotFoundException ->
            "Este trabalho de impressão não existe mais (pode já ter sido concluído ou removido)."
        is InvalidPrinterAddressException ->
            "O endereço configurado para a impressora é inválido. Verifique o IP em Impressoras."
        is InvalidPortException ->
            "A porta configurada para a impressora é inválida. Verifique em Impressoras."
        is InvalidStoreException ->
            "Os dados da loja são inválidos. Verifique o código e o nome da loja."
        is InvalidDeviceException ->
            "Os dados do dispositivo são inválidos. Verifique o nome e o identificador."
        is LicenseRestrictedException ->
            translateLicenseRestricted(error)
        is LabelTemplateNotFoundException ->
            "Este modelo de etiqueta não existe mais. Volte para Etiquetas e escolha outro."
        is InvalidLabelTemplateException ->
            "O modelo de etiqueta tem um problema: ${error.detail}"
        is NoDefaultLabelTemplateException ->
            "Nenhum modelo de etiqueta padrão configurado. Escolha um em Etiquetas."
        is UnknownTemplateException ->
            "O modelo de etiqueta pedido não existe neste aparelho. Atualize o app que enviou a impressão."
        is MissingTemplateVariablesException ->
            "Faltam dados para imprimir a etiqueta: ${error.missing.joinToString()}. Nada foi impresso."
        is InvalidTemplateValueException ->
            "O campo ${error.variable} tem um caractere que a impressora não aceita. Nada foi impresso."
        is TemplateLanguageMismatchException ->
            "Esta etiqueta é ${error.templateLanguage}, mas a impressora está como ${error.printerLanguage}. Ajuste a linguagem em Impressoras."
        is DocumentRasterizationException ->
            "Não foi possível preparar o documento para impressão: ${error.detail}."
        is InvalidMediaProfileException ->
            "Configuração de papel/DPI inválida: ${error.detail}. Verifique em Impressoras."
        is PrinterCapabilityException ->
            error.detail.replaceFirstChar { it.uppercase() } + "."
    }

    private fun translateLicenseRestricted(error: LicenseRestrictedException): String = when (error.effectiveStatus) {
        "NOT_ACTIVATED" -> "Este dispositivo ainda não foi ativado. Contate o suporte."
        "RESTRICTED" -> "A licença deste dispositivo não está ativa no momento. Verifique a conexão com a internet; se persistir, contate o suporte."
        else -> "Este dispositivo não pode criar novas impressões no momento. Contate o suporte."
    }
}
