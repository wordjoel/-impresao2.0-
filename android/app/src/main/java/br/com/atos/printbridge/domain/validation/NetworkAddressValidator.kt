package br.com.atos.printbridge.domain.validation

/**
 * Validação puramente sintática de IPv4/hostname e porta — não requer resolução de DNS nem
 * acesso à rede, por isso vive no domain sem depender de Android/java.net.
 */
object NetworkAddressValidator {

    private val IPV4_REGEX = Regex(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )

    private val HOSTNAME_REGEX = Regex(
        "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]{0,61}[A-Za-z0-9])$"
    )

    // Forma "N.N.N.N" (todos os rótulos numéricos) — se cair nesse formato, precisa passar
    // pela validação estrita de IPv4 (0-255 por octeto). Sem essa guarda, HOSTNAME_REGEX
    // aceitaria endereços como "999.999.1.1" como "hostname válido" (DNS permite rótulos
    // só de dígitos), mascarando um IP digitado errado.
    private val DOTTED_NUMERIC_SHAPE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isValidAddress(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (DOTTED_NUMERIC_SHAPE.matches(trimmed)) return IPV4_REGEX.matches(trimmed)
        return HOSTNAME_REGEX.matches(trimmed)
    }

    /** IPv4 estrito (0-255 por octeto) — usado para escolher o endereço certo do NSD, que pode
     *  devolver IPv6/link-local junto. */
    fun isIpv4(value: String): Boolean = IPV4_REGEX.matches(value.trim())

    fun isValidPort(port: Int): Boolean = port in 1..65535
}
