package br.com.atos.printbridge.network

import br.com.atos.printbridge.domain.discovery.LocalIpv4
import br.com.atos.printbridge.domain.discovery.LocalNetworkInfoProvider
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

/**
 * Primeiro IPv4 site-local (não loopback, não link-local) da máquina, com o prefixo da interface.
 * É o "ponto de ancoragem" para a varredura de subnet (Fase 5): a impressora tende a estar na
 * mesma rede do Android. Enumeração de NetworkInterface não exige permissão Android.
 */
class AndroidLocalNetworkInfoProvider @Inject constructor() : LocalNetworkInfoProvider {

    override fun current(): LocalIpv4? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { it.name }
            .flatMap { nic ->
                nic.interfaceAddresses.mapNotNull { ia ->
                    val address = ia.address
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        LocalIpv4(
                            address = address.hostAddress ?: return@mapNotNull null,
                            prefixLength = ia.networkPrefixLength.toInt(),
                        )
                    } else {
                        null
                    }
                }
            }
            .firstOrNull()
    }.getOrNull()
}