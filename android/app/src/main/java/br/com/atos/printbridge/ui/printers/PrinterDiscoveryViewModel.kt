package br.com.atos.printbridge.ui.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.DiscoverPrintersUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.SaveDiscoveredPrinterUseCase
import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveryListing
import br.com.atos.printbridge.domain.discovery.LocalNetworkInfoProvider
import br.com.atos.printbridge.domain.discovery.RegisteredPrinterMatch
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.util.ErrorTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrinterDiscoveryUiState(
    val isScanning: Boolean = false,
    val finished: Boolean = false,
    /** Rede real do Android nesta rodada (IP/prefixo), ou nulo sem IPv4. */
    val localNetwork: String? = null,
    val networkAvailable: Boolean = true,
    val results: List<DiscoveredPrinter> = emptyList(),
    /** Impressoras já cadastradas neste aparelho — aparecem na busca mesmo sem responder. */
    val registered: List<Printer> = emptyList(),
    val message: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
) {
    val listing: DiscoveryListing get() = RegisteredPrinterMatch.list(registered, results)
}

/**
 * Descoberta sob demanda: começa ao abrir a tela e em "Procurar novamente". Sair da tela
 * cancela mDNS, varredura e identificação (viewModelScope).
 */
@HiltViewModel
class PrinterDiscoveryViewModel @Inject constructor(
    private val discoverPrintersUseCase: DiscoverPrintersUseCase,
    private val saveDiscoveredPrinterUseCase: SaveDiscoveredPrinterUseCase,
    private val localNetworkInfoProvider: LocalNetworkInfoProvider,
    private val networkStateProvider: NetworkStateProvider,
    observePrintersUseCase: ObservePrintersUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PrinterDiscoveryUiState())
    val state: StateFlow<PrinterDiscoveryUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Ao adicionar uma impressora achada, ela sai de "novas" e entra em "cadastradas" por aqui.
        viewModelScope.launch {
            observePrintersUseCase().collect { printers -> _state.update { it.copy(registered = printers) } }
        }
        start()
    }

    fun start() {
        scanJob?.cancel()
        val local = localNetworkInfoProvider.current()
        val online = networkStateProvider.isNetworkAvailable()
        _state.value = PrinterDiscoveryUiState(
            isScanning = online,
            finished = !online,
            localNetwork = local?.let { "${it.address}/${it.prefixLength}" },
            networkAvailable = online,
            registered = _state.value.registered,
            startedAt = System.currentTimeMillis(),
        )
        if (!online) return

        scanJob = viewModelScope.launch {
            try {
                discoverPrintersUseCase().collect { found ->
                    _state.update { s ->
                        s.copy(results = (s.results.filterNot { it.dedupeKey == found.dedupeKey } + found).sortedBy { ipOrder(it.ipAddress) })
                    }
                }
                _state.update { it.copy(isScanning = false, finished = true, finishedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false, finished = true, message = "Falha na busca: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(isScanning = false, finished = true, finishedAt = System.currentTimeMillis()) }
    }

    fun save(discovered: DiscoveredPrinter) {
        _state.value.registered.firstOrNull { it.ipAddress == discovered.ipAddress }?.let { existing ->
            _state.update { it.copy(message = "${discovered.ipAddress} já está cadastrada como \"${existing.name}\".") }
            return
        }
        viewModelScope.launch {
            runCatching { saveDiscoveredPrinterUseCase(discovered) }
                .onSuccess { p ->
                    _state.update { it.copy(message = "\"${p.name}\" cadastrada (${p.protocol.label}, porta ${p.port}).") }
                }
                .onFailure { e -> _state.update { it.copy(message = ErrorTranslator.translate(e)) } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun ipOrder(ip: String): Long = ip.split('.').fold(0L) { acc, part -> acc * 256 + (part.toLongOrNull() ?: 0) }
}
