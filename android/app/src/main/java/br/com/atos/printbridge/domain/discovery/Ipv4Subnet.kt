package br.com.atos.printbridge.domain.discovery

/**
 * Cálculo puro de quais hosts IPv4 varrer numa subnet — sem Android/java.net, por isso vive no
 * domain e é testável em JVM pura. A varredura real (abrir socket) fica na camada de plataforma.
 */
object Ipv4Subnet {

    private const val ALL_BITS = 0xFFFFFFFFL

    fun toLong(address: String): Long? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    fun toAddress(value: Long): String {
        val a = (value shr 24) and 0xFF
        val b = (value shr 16) and 0xFF
        val c = (value shr 8) and 0xFF
        val d = value and 0xFF
        return "$a.$b.$c.$d"
    }

    /**
     * Hosts utilizáveis da subnet, no máximo [maxHosts]. Quando a subnet tem mais hosts que o teto
     * (ex.: um /16 corporativo), devolve uma janela centrada no endereço do próprio device — nunca
     * a subnet inteira, que levaria minutos. Rede e broadcast nunca entram (não são hosts).
     */
    fun hostsToScan(address: String, prefixLength: Int, maxHosts: Int): List<String> {
        if (maxHosts <= 0) return emptyList()
        val ip = toLong(address) ?: return emptyList()
        val prefix = prefixLength.coerceIn(0, 32)
        val mask = if (prefix == 0) 0L else (ALL_BITS shl (32 - prefix)) and ALL_BITS
        val network = ip and mask
        val broadcast = network or (ALL_BITS xor mask)
        val firstHost = network + 1
        val lastHost = broadcast - 1
        if (firstHost > lastHost) return emptyList()

        val total = lastHost - firstHost + 1
        if (total <= maxHosts) {
            return (firstHost..lastHost).map { toAddress(it) }
        }

        val half = maxHosts / 2L
        var start = ip - half
        if (start < firstHost) start = firstHost
        val maxStart = lastHost - maxHosts + 1
        if (start > maxStart) start = maxStart
        val end = start + maxHosts - 1
        return (start..end).map { toAddress(it) }
    }
}
