package br.com.atos.printbridge.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.DeletePrintJobByOperatorUseCase
import br.com.atos.printbridge.application.usecase.ObserveActivePrintJobsUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.RetryPrintJobUseCase
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.util.launchCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class QueueUiState(
    val jobs: List<PrintJob> = emptyList(),
    val printerNames: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    observeActivePrintJobsUseCase: ObserveActivePrintJobsUseCase,
    observePrintersUseCase: ObservePrintersUseCase,
    private val retryPrintJobUseCase: RetryPrintJobUseCase,
    private val deletePrintJobByOperatorUseCase: DeletePrintJobByOperatorUseCase,
) : ViewModel() {

    val uiState: StateFlow<QueueUiState> = combine(
        observeActivePrintJobsUseCase(),
        observePrintersUseCase(),
    ) { jobs, printers ->
        QueueUiState(
            jobs = jobs,
            printerNames = printers.associate { it.id to it.name },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState())

    fun retry(jobId: String) = launchCatching("job_retry_failed") { retryPrintJobUseCase(jobId) }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun delete(jobId: String) = launchCatching("job_delete_failed") {
        if (!deletePrintJobByOperatorUseCase(jobId)) {
            _message.value = "Não foi removido: o job já está sendo enviado ou já terminou."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
