package br.com.atos.printbridge.domain.snmp

/** O que o agente SNMP da impressora respondeu. Qualquer campo pode faltar. */
data class SnmpIdentity(
    val sysDescr: String?,
    val sysName: String?,
    /** hrDeviceDescr.1 — em impressoras de rede costuma ser "Fabricante Modelo". */
    val deviceDescr: String?,
) {
    /** Melhor descrição do equipamento, na ordem de confiabilidade para impressoras. */
    val bestDescription: String? get() = deviceDescr ?: sysDescr

    /**
     * Fabricante só quando a descrição começa com um nome de fabricante de impressora conhecido.
     * A lista existe para NÃO transformar a primeira palavra de qualquer texto em fabricante
     * (sysDescr de roteador, "Linux 4.x ..." etc.); sem casamento, fica "Não informado".
     */
    val manufacturer: String?
        get() {
            val text = bestDescription ?: return null
            return KNOWN_MANUFACTURERS.firstOrNull { text.startsWith(it, ignoreCase = true) }
        }

    val model: String?
        get() {
            val text = bestDescription ?: return null
            val mfg = manufacturer ?: return null
            return text.substring(mfg.length).trim().substringBefore(';').ifEmpty { null }
        }

    companion object {
        val KNOWN_MANUFACTURERS = listOf(
            "HP", "Hewlett-Packard", "Samsung", "Brother", "Canon", "Epson", "Lexmark", "Xerox", "Kyocera",
            "Ricoh", "Sharp", "Konica Minolta", "OKI", "Zebra", "TSC", "Honeywell", "Datamax", "SATO",
            "Elgin", "Bematech", "Argox", "Pantum", "Dell", "Toshiba",
        )
    }
}

interface SnmpIdentityProbe {
    suspend fun identify(ipAddress: String, timeoutMillis: Int): SnmpIdentity?
}
