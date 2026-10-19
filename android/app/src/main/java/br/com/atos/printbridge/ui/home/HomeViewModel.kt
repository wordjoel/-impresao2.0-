package br.com.atos.printbridge.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.CheckPrinterStatusUseCase
import br.com.atos.printbridge.application.usecase.ObserveActivePrintJobsUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintJobHistoryUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.PrintTestPageUseCase
import br.com.atos.printbridge.application.usecase.PrinterStatusReport
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.queue.RecentFailures
import br.com.atos.printbridge.util.ErrorTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

data class HomeUiState(
    val defaultPrinter: Printer? = null,
    /** Checagem real da impressora padrão (nulo = ainda não checada / nenhuma padrão). */
    val defaultPrinterStatus: PrinterStatusReport? = null,
    val networkAvailable: Boolean = true,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val isLoading: Boolean = true,
    val testPrintFeedback: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observePrintersUseCase: ObservePrintersUseCase,
    observeActivePrintJobsUseCase: ObserveActivePrintJobsUseCase,
    observePrintJobHistoryUseCase: ObservePrintJobHistoryUseCase,
    private val printTestPageUseCase: PrintTestPageUseCase,
    private val checkPrinterStatusUseCase: CheckPrinterStatusUseCase,
    private val networkStateProvider: NetworkStateProvider,
) : ViewModel() {

    private val defaultStatus = MutableStateFlow<PrinterStatusReport?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        observePrintersUseCase(),
        observeActivePrintJobsUseCase(),
        observePrintJobHistoryUseCase(),
        defaultStatus,
        // Relógio da janela de 24 h: sem ele, uma falha "envelheceria" só quando outra coisa mudasse.
        flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } },
    ) { printers, activeJobs, historyJobs, status, now ->
        HomeUiState(
            defaultPrinter = printers.firstOrNull { it.isDefault },
            defaultPrinterStatus = status,
            networkAvailable = networkStateProvider.isNetworkAvailable(),
            pendingCount = activeJobs.count { it.status == PrintJobStatus.PENDING },
            failedCount = RecentFailures.count(historyJobs, now, printers.mapTo(HashSet()) { it.id }),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    init {
        // Padrão trocada/cadastrada → checa de verdade; nunca mostra "disponível" sem resposta.
        viewModelScope.launch {
            observePrintersUseCase()
                .map { list -> list.firstOrNull { it.isDefault } }
                .distinctUntilChangedBy { it?.let { p -> "${p.id}|${p.ipAddress}|${p.port}|${p.protocol}" } }
                .collect { printer ->
                    defaultStatus.value = null
                    if (printer != null) defaultStatus.value = checkPrinterStatusUseCase(printer)
                }
        }
    }

    fun printSampleLabel() {
        viewModelScope.launch {
            val printer = uiState.value.defaultPrinter
            if (printer == null) {
                _feedback.value = "Nenhuma impressora padrão configurada. Escolha uma em Impressoras."
                return@launch
            }
            runCatching { printTestPageUseCase(printer.id) }
                .onSuccess { _feedback.value = "Teste enviado para a fila (${it.type}, job ${it.id.take(8)})." }
                // Nunca it.message diretamente — ErrorTranslator decide o que o operador vê.
                .onFailure { _feedback.value = ErrorTranslator.translate(it) }
            defaultStatus.value = checkPrinterStatusUseCase(printer)
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
