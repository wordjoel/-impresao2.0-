package br.com.atos.printbridge.di

import br.com.atos.printbridge.domain.discovery.HostReachabilityProbe
import br.com.atos.printbridge.domain.discovery.LocalNetworkInfoProvider
import br.com.atos.printbridge.domain.discovery.MdnsPrinterDiscovery
import br.com.atos.printbridge.domain.discovery.PrinterDiscovery
import br.com.atos.printbridge.domain.discovery.SubnetScanner
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import br.com.atos.printbridge.domain.printing.PrinterTransport
import br.com.atos.printbridge.domain.queue.QueueDispatcher
import br.com.atos.printbridge.domain.raster.DocumentRasterizer
import br.com.atos.printbridge.network.AndroidLocalNetworkInfoProvider
import br.com.atos.printbridge.network.AndroidNetworkStateProvider
import br.com.atos.printbridge.printing.PrinterConnectionTesterImpl
import br.com.atos.printbridge.printing.TcpPrinterTransport
import br.com.atos.printbridge.printing.discovery.AndroidNsdPrinterDiscovery
import br.com.atos.printbridge.printing.discovery.PrinterDiscoveryServiceImpl
import br.com.atos.printbridge.printing.discovery.TcpHostReachabilityProbe
import br.com.atos.printbridge.printing.discovery.TcpSubnetScanner
import br.com.atos.printbridge.printing.raster.AndroidDocumentRasterizer
import br.com.atos.printbridge.worker.WorkManagerQueueDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrintingModule {

    @Binds
    @Singleton
    abstract fun bindPrinterTransport(impl: TcpPrinterTransport): PrinterTransport

    @Binds
    @Singleton
    abstract fun bindPrinterConnectionTester(impl: PrinterConnectionTesterImpl): PrinterConnectionTester

    @Binds
    @Singleton
    abstract fun bindNetworkStateProvider(impl: AndroidNetworkStateProvider): NetworkStateProvider

    @Binds
    @Singleton
    abstract fun bindQueueDispatcher(impl: WorkManagerQueueDispatcher): QueueDispatcher

    @Binds
    @Singleton
    abstract fun bindPrinterDiscovery(impl: PrinterDiscoveryServiceImpl): PrinterDiscovery

    @Binds
    @Singleton
    abstract fun bindMdnsPrinterDiscovery(impl: AndroidNsdPrinterDiscovery): MdnsPrinterDiscovery

    @Binds
    @Singleton
    abstract fun bindSubnetScanner(impl: TcpSubnetScanner): SubnetScanner

    @Binds
    @Singleton
    abstract fun bindHostReachabilityProbe(impl: TcpHostReachabilityProbe): HostReachabilityProbe

    @Binds
    @Singleton
    abstract fun bindLocalNetworkInfoProvider(impl: AndroidLocalNetworkInfoProvider): LocalNetworkInfoProvider

    @Binds
    @Singleton
    abstract fun bindHostnameResolver(impl: br.com.atos.printbridge.printing.discovery.AndroidHostnameResolver): br.com.atos.printbridge.domain.discovery.HostnameResolver

    @Binds
    abstract fun bindTestPageRenderer(impl: br.com.atos.printbridge.printing.testpage.AndroidTestPageRenderer): br.com.atos.printbridge.domain.printing.TestPageRenderer

    @Binds
    @Singleton
    abstract fun bindIppGateway(impl: br.com.atos.printbridge.printing.ipp.HttpIppGateway): br.com.atos.printbridge.domain.ipp.IppGateway

    @Binds
    @Singleton
    abstract fun bindIppJobTransport(impl: br.com.atos.printbridge.printing.ipp.IppPrinterTransport): br.com.atos.printbridge.domain.printing.IppJobTransport

    @Binds
    @Singleton
    abstract fun bindSnmpIdentityProbe(impl: br.com.atos.printbridge.printing.snmp.UdpSnmpIdentityProbe): br.com.atos.printbridge.domain.snmp.SnmpIdentityProbe

    @Binds
    @Singleton
    abstract fun bindDocumentRasterizer(impl: AndroidDocumentRasterizer): DocumentRasterizer
}
