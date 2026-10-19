package br.com.atos.printbridge.printing.discovery

import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveredService
import br.com.atos.printbridge.domain.discovery.DiscoverySource
import br.com.atos.printbridge.domain.discovery.MdnsServiceHit
import br.com.atos.printbridge.domain.discovery.PrintServiceProtocol
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.validation.NetworkAddressValidator

/**
 * Aplica um serviço DNS-SD resolvido ao host descoberto — lógica pura e testável em JVM. Usa só
 * o que a impressora anunciou no TXT (RFC 6763 / Bonjour Printing Spec): `ty`, `usb_MFG`,
 * `usb_MDL`, `product`, `rp`.
 */
object NsdServiceMapper {

    fun apply(device: DiscoveredPrinter?, hit: MdnsServiceHit): DiscoveredPrinter? {
        if (!NetworkAddressValidator.isIpv4(hit.hostAddress)) return null
        val protocol = PrintServiceProtocol.ofMdnsType(hit.serviceType) ?: return null
        val base = device ?: DiscoveredPrinter(ipAddress = hit.hostAddress)

        val portInformed = NetworkAddressValidator.isValidPort(hit.port)
        val service = DiscoveredService(
            protocol = protocol,
            // Android NSD às vezes resolve porta 0: aí a porta IANA do tipo anunciado é só um
            // candidato a verificar, marcado assim na nota — nunca tratado como confirmado.
            port = if (portInformed) hit.port else protocol.ianaPort,
            resourcePath = text(hit.txt, "rp")?.trimStart('/'),
            sources = setOf(DiscoverySource.MDNS),
            // RAW anunciado pelo próprio equipamento como _pdl-datastream é serviço de impressão
            // declarado; IPP só vale depois de responder (verificação feita na identificação).
            verified = protocol == PrintServiceProtocol.RAW && portInformed,
            note = when {
                !portInformed -> "Porta não informada pelo mDNS; verificando a porta registrada ${protocol.ianaPort}"
                protocol == PrintServiceProtocol.IPPS -> "IPPS (TLS) não suportado pelo Print Bridge; use o serviço IPP"
                protocol == PrintServiceProtocol.LPD -> "LPD anunciado; o Print Bridge não envia por LPD"
                protocol == PrintServiceProtocol.RAW -> "Anunciado via mDNS como $TYPE_RAW"
                else -> null
            },
        )

        val mfg = text(hit.txt, "usb_MFG")
        val mdl = text(hit.txt, "usb_MDL")
        val product = text(hit.txt, "product")?.removeSurrounding("(", ")")
        val ty = text(hit.txt, "ty")
        val hasTxtIdentity = mfg != null || mdl != null || product != null || ty != null
        // Identidade vinda do IPP é mais confiável e não é sobrescrita pelo TXT.
        val keepExisting = base.identitySource == SOURCE_IPP
        return base.withService(service).copy(
            hostname = base.hostname ?: hit.hostName?.takeUnless { it == hit.hostAddress || it.isBlank() },
            name = base.name ?: hit.serviceName.takeIf { it.isNotBlank() },
            manufacturer = if (keepExisting) base.manufacturer else base.manufacturer ?: mfg,
            model = if (keepExisting) base.model else base.model ?: mdl ?: product ?: ty,
            identitySource = base.identitySource ?: SOURCE_MDNS.takeIf { hasTxtIdentity },
        )
    }

    private fun text(txt: Map<String, String>, key: String): String? =
        txt.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

    const val SOURCE_IPP = "IPP"
    const val SOURCE_SNMP = "SNMP"
    const val SOURCE_MDNS = "mDNS"
    private const val TYPE_RAW = "_pdl-datastream._tcp"

    /**
     * Modelo TSC reconhecido no que a impressora ANUNCIOU (nome/TXT do mDNS ou SNMP): mantém a família
     * TSC cadastrável como antes, sem presumir nada quando o texto não diz TSC.
     */
    fun modelHint(text: String?): PrinterModel? {
        val upper = text?.uppercase() ?: return null
        return when {
            "MB241" in upper -> PrinterModel.TSC_MB241
            "ML241" in upper -> PrinterModel.TSC_ML241
            "TSC" in upper -> PrinterModel.OTHER_TSC
            else -> null
        }
    }
}
