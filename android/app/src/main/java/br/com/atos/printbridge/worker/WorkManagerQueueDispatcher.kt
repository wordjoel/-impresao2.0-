package br.com.atos.printbridge.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import br.com.atos.printbridge.domain.queue.QueueDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WorkManagerQueueDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) : QueueDispatcher {

    override fun requestProcessing() {
        val request = OneTimeWorkRequestBuilder<PrintQueueWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PrintQueueWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /**
     * Rede de segurança: mesmo sem nenhum job novo, roda a fila a cada 15 min (mínimo permitido
     * pelo WorkManager) para reprocessar jobs PENDING que não geraram novo requestProcessing()
     * — por exemplo, um retry que ficou pendente após o app ser fechado antes do backoff acabar.
     */
    fun scheduleSafetyNet() {
        val request = PeriodicWorkRequestBuilder<PrintQueueWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "${PrintQueueWorker.UNIQUE_WORK_NAME}_safety_net",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
