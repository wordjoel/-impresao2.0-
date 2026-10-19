package br.com.atos.printbridge.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.ObservePrintJobHistoryUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.ReprintJobUseCase
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.util.launchCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class PeriodFilter(val label: String, val millis: Long?) {
    ALL("Tudo", null),
    LAST_24H("Últimas 24h", 24 * 60 * 60 * 1000L),
    LAST_7D("Últimos 7 dias", 7 * 24 * 60 * 60 * 1000L),
    LAST_30D("Últimos 30 dias", 30 * 24 * 60 * 60 * 1000L),
}

data class HistoryFilter(
    val printerId: String? = null,
    val status: PrintJobStatus? = null,
    val period: PeriodFilter = PeriodFilter.ALL,
)

data class HistoryUiState(
    val allJobs: List<PrintJob> = emptyList(),
    val printerNames: Map<String, String> = emptyMap(),
    val filter: HistoryFilter = HistoryFilter(),
    val isLoading: Boolean = true,
) {
    val filteredJobs: List<PrintJob>
        get() {
            val periodCutoff = filter.period.millis?.let { System.currentTimeMillis() - it }
            return allJobs.filter { job ->
                (filter.printerId == null || job.printerId == filter.printerId) &&
                    (filter.status == null || job.status == filter.status) &&
                    (periodCutoff == null || job.updatedAt >= periodCutoff)
            }
        }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    observePrintJobHistoryUseCase: ObservePrintJobHistoryUseCase,
    observePrintersUseCase: ObservePrintersUseCase,
    private val reprintJobUseCase: ReprintJobUseCase,
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter())

    val uiState: StateFlow<HistoryUiState> = combine(
        observePrintJobHistoryUseCase(),
        observePrintersUseCase(),
        _filter,
    ) { jobs, printers, filter ->
        HistoryUiState(
            allJobs = jobs,
            printerNames = printers.associate { it.id to it.name },
            filter = filter,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setStatusFilter(status: PrintJobStatus?) {
        _filter.value = _filter.value.copy(status = status)
    }

    fun setPrinterFilter(printerId: String?) {
        _filter.value = _filter.value.copy(printerId = printerId)
    }

    fun setPeriodFilter(period: PeriodFilter) {
        _filter.value = _filter.value.copy(period = period)
    }

    fun reprint(jobId: String) = launchCatching("job_reprint_failed") { reprintJobUseCase(jobId) }
}
