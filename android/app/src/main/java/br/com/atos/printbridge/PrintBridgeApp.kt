package br.com.atos.printbridge

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import br.com.atos.printbridge.license.ManagedLicenseLink
import br.com.atos.printbridge.notification.PrintStatusNotifier
import br.com.atos.printbridge.provisioning.ProvisioningCoordinator
import br.com.atos.printbridge.worker.WorkManagerQueueDispatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PrintBridgeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var queueDispatcher: WorkManagerQueueDispatcher

    @Inject
    lateinit var printStatusNotifier: PrintStatusNotifier

    @Inject
    lateinit var provisioningCoordinator: ProvisioningCoordinator

    @Inject
    lateinit var managedLicenseLink: ManagedLicenseLink

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        queueDispatcher.scheduleSafetyNet()
        queueDispatcher.requestProcessing()
        // Confirmação visual (Fase 1, Etapa 7): todo job termina em "enviado" ou "falha" visível.
        printStatusNotifier.start()
        // Instalação em escala: configuração da loja vinda do MDM (ou do arquivo da bancada).
        provisioningCoordinator.start()
        // D2: sem agente embutido. Publica o identificador de licença ao agente oficial
        // (com.onmdm.agent) e cancela o trabalho periódico herdado da versão com agente embutido.
        managedLicenseLink.start()
    }
}
