package br.com.atos.printbridge.domain.license

/**
 * Identidade do dispositivo ancorada em hardware seguro (Android Keystore/TEE/StrongBox).
 * A chave privada correspondente nunca é exposta fora do hardware — apenas a operação de
 * assinatura é exposta via [sign]. Copiar o pacote instalado do app para outro aparelho NÃO
 * copia essa chave; o novo aparelho gera a sua própria e se apresenta ao backend como um
 * dispositivo novo. Ver CARREFOUR-PRINT-SECURITY-MODEL.md §2.
 */
interface DeviceIdentityProvider {
    /** Identificador local estável, derivado da chave pública — não é dado pessoal (sem IMEI/serial). */
    fun deviceId(): String

    /** Chave pública (X.509 SubjectPublicKeyInfo) do par gerado no hardware seguro. Gerada sob demanda na primeira chamada. */
    fun publicKey(): ByteArray

    /** Assina [challenge] com a chave privada não exportável — usado na prova de posse contínua (§4). */
    fun sign(challenge: ByteArray): ByteArray
}
