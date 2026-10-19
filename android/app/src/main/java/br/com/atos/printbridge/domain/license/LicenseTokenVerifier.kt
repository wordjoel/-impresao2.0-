package br.com.atos.printbridge.domain.license

import br.com.atos.printbridge.domain.model.LicenseTokenPayload

/**
 * Verifica um License Token compacto ("payload.assinatura", Base64Url) contra a chave pública do
 * servidor embutida no app — nunca contra uma chave obtida em runtime (isso permitiria a um
 * atacante em MITM substituir a chave e forjar tokens). Pura função criptográfica, sem estado,
 * sem I/O — testável sem qualquer infraestrutura. CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 */
interface LicenseTokenVerifier {
    /** Retorna o payload verificado, ou null se a assinatura não confere ou o token é malformado. */
    fun verify(compactToken: String): LicenseTokenPayload?
}
