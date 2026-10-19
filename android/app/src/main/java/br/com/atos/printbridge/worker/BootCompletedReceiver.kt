package br.com.atos.printbridge.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Dispositivos corporativos em ambiente operacional podem ser reiniciados sem que um humano
 * reabra o app manualmente. Este receiver garante que jobs pendentes voltem a ser processados
 * assim que o Android termina o boot, sem depender de o operador lembrar de abrir o Print Bridge.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var queueDispatcher: WorkManagerQueueDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.event(LogLevel.INFO, "boot_completed_received")
        queueDispatcher.scheduleSafetyNet()
        queueDispatcher.requestProcessing()
    }
}
