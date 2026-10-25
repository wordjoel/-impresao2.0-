package br.com.atos.printbridge.license

import android.content.Context
import androidx.enterprise.feedback.KeyedAppState
import androidx.enterprise.feedback.KeyedAppStatesReporter
import br.com.atos.printbridge.domain.status.PrintStatusBuilder
import br.com.atos.printbridge.domain.status.PrintStatusEncoder
import br.com.atos.printbridge.domain.status.PrintStatusSeverity
import br.com.atos.printbridge.domain.status.PrintStatusSource
import br.com.atos.printbridge.domain.status.PrinterState
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Item 2 do controle pelo MDM: informa ao agente oficial (com.onmdm.agent, dono do aparelho) o
 * estado da fila e das impressoras, como Keyed App State [PrintStatusEncoder.STATE_KEY] — o mesmo
 * canal do identificador de licença ([ManagedLicenseLink]). O agente repassa ao gateway no
 * heartbeat. Só liga com MDM presente (ver ProvisioningCoordinator). Sem rede, sem serviço
 * residente: roda enquanto o processo do app está vivo,
 * reagindo às mudanças da fila (com espera para juntar rajadas) e, sem mudança, a cada
 * [ManagedStatusPolicy.REFRESH_MILLIS] como sinal de vida.
 */
@Singleton
class ManagedStatusReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: PrintStatusSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastFingerprint: String? = null
    private var lastPublishedAt = 0L

    private val started = AtomicBoolean(false)

    /** Idempotente: o ProvisioningCoordinator chama a cada leitura da configuração gerenciada. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(ManagedStatusPolicy.REFRESH_MILLIS)
                }
            }
            merge(source.changes().map { }, ticker)
                .debounce(ManagedStatusPolicy.DEBOUNCE_MILLIS)
                .collect { publishIfDue() }
        }
    }

    private suspend fun publishIfDue() = mutex.withLock {
        runCatching {
            val now = System.currentTimeMillis()
            val snapshot = PrintStatusBuilder.build(source, now)
            val fingerprint = PrintStatusEncoder.fingerprint(snapshot)
            if (!ManagedStatusPolicy.shouldPublish(fingerprint, lastFingerprint, lastPublishedAt, now)) return@runCatching
            val severity = PrintStatusEncoder.severity(snapshot)
            val state = KeyedAppState.builder()
                .setKey(PrintStatusEncoder.STATE_KEY)
                .setSeverity(if (severity == PrintStatusSeverity.ERROR) KeyedAppState.SEVERITY_ERROR else KeyedAppState.SEVERITY_INFO)
                .setMessage(ManagedStatusPolicy.message(snapshot.pending + snapshot.processing, snapshot.printers.count { it.state == PrinterState.FAILING }))
                .setData(PrintStatusEncoder.encode(snapshot))
                .build()
            KeyedAppStatesReporter.create(context).setStates(listOf(state))
            lastFingerprint = fingerprint
            lastPublishedAt = now
            Logger.event(LogLevel.INFO, "print_status_published", extra = mapOf("severity" to severity.name, "printers" to snapshot.printers.size))
        }.onFailure {
            Logger.event(LogLevel.WARN, "print_status_publish_failed", extra = mapOf("error" to (it.message ?: it::class.simpleName)))
        }
    }
}

/** Regras puras de quando publicar (testadas na JVM). */
object ManagedStatusPolicy {
    const val DEBOUNCE_MILLIS = 15_000L
    const val REFRESH_MILLIS = 15 * 60 * 1000L

    fun shouldPublish(fingerprint: String, last: String?, lastAt: Long, now: Long): Boolean =
        fingerprint != last || now - lastAt >= REFRESH_MILLIS

    fun message(activeJobs: Int, failingPrinters: Int): String = buildString {
        append("Fila: $activeJobs")
        if (failingPrinters > 0) append(" · impressoras com falha: $failingPrinters")
    }
}
