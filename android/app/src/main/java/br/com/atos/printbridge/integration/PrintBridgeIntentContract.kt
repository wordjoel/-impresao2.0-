package br.com.atos.printbridge.integration

/**
 * Contrato público de integração do Atos Print Bridge. Ver INTEGRATION.md na raiz do projeto
 * para o guia completo (com exemplo Kotlin) destinado ao desenvolvedor do app cliente.
 *
 * Ação customizada (protegida por permissão "signature", ver AndroidManifest.xml):
 *   br.com.atos.printbridge.action.SUBMIT_PRINT_JOB
 *
 * Extras aceitos:
 *   EXTRA_ZPL_CONTENT (String, obrigatório)       — conteúdo ZPL bruto
 *   EXTRA_DOCUMENT_NAME (String, opcional)         — nome amigável do documento
 *   EXTRA_COPIES (Int, opcional, default 1)
 *   EXTRA_PRINTER_ID (String, opcional)            — se ausente, usa a impressora padrão
 *   EXTRA_EXTERNAL_DOCUMENT_ID (String, opcional)  — identificador do documento no sistema cliente
 */
object PrintBridgeIntentContract {
    const val ACTION_SUBMIT_PRINT_JOB = "br.com.atos.printbridge.action.SUBMIT_PRINT_JOB"
    const val PERMISSION_SUBMIT_PRINT_JOB = "br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB"

    const val EXTRA_ZPL_CONTENT = "br.com.atos.printbridge.extra.ZPL_CONTENT"
    const val EXTRA_DOCUMENT_NAME = "br.com.atos.printbridge.extra.DOCUMENT_NAME"
    const val EXTRA_COPIES = "br.com.atos.printbridge.extra.COPIES"
    const val EXTRA_PRINTER_ID = "br.com.atos.printbridge.extra.PRINTER_ID"
    const val EXTRA_EXTERNAL_DOCUMENT_ID = "br.com.atos.printbridge.extra.EXTERNAL_DOCUMENT_ID"

    const val RESULT_EXTRA_JOB_ID = "br.com.atos.printbridge.extra.RESULT_JOB_ID"
    const val RESULT_EXTRA_ERROR = "br.com.atos.printbridge.extra.RESULT_ERROR"
}
