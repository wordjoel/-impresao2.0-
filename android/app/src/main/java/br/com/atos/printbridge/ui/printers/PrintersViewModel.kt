package br.com.atos.printbridge.ui.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.CheckPrinterStatusUseCase
import br.com.atos.printbridge.application.usecase.DeletePrinterUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.PrintTestPageUseCase
import br.com.atos.printbridge.application.usecase.PrinterStatusReport
import br.com.atos.printbridge.application.usecase.SetDefaultPrinterUseCase
import br.com.atos.printbridge.application.usecase.SetPrinterEnabledUseCase
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.util.ErrorTranslator
import br.com.atos.printbridge.util.launchCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PrinterStatusState {
    data object Checking : PrinterStatusState()
    data class Done(val report: PrinterStatusReport) : PrinterStatusState()
}

@HiltViewModel
class PrintersViewModel @Inject constructor(
    observePrintersUseCase: ObservePrintersUseCase,
    private val deletePrinterUseCase: DeletePrinterUseCase,
    private val setDefaultPrinterUseCase: SetDefaultPrinterUseCase,
    private val setPrinterEnabledUseCase: SetPrinterEnabledUseCase,
    private val checkPrinterStatusUseCase: CheckPrinterStatusUseCase,
    private val printTestPageUseCase: PrintTestPageUseCase,
) : ViewModel() {

    /** Nulo = ainda lendo o banco — a tela não pode dizer "não foram encontradas" antes de saber. */
    val printers: StateFlow<List<Printer>?> = observePrintersUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _status = MutableStateFlow<Map<String, PrinterStatusState>>(emptyMap())
    val status: StateFlow<Map<String, PrinterStatusState>> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Status real ao abrir a tela: cada impressora é consultada uma vez; nada é "Conectada" sem resposta.
        viewModelScope.launch {
            observePrintersUseCase().first().forEach { checkStatus(it) }
        }
    }

    fun checkStatus(printer: Printer) {
        viewModelScope.launch {
            _status.update { it + (printer.id to PrinterStatusState.Checking) }
            val report = checkPrinterStatusUseCase(printer)
            _status.update { it + (printer.id to PrinterStatusState.Done(report)) }
        }
    }

    fun printTest(printer: Printer) {
        viewModelScope.launch {
            runCatching { printTestPageUseCase(printer.id) }
                .onSuccess { _message.value = "Teste enviado para a fila (${it.type}, job ${it.id.take(8)}). Acompanhe em Fila/Histórico." }
                .onFailure { _message.value = "Não foi possível gerar o teste: ${ErrorTranslator.translate(it)}" }
        }
    }

    fun clearMessage() { _message.value = null }

    fun delete(id: String) = launchCatching("printer_delete_failed") { deletePrinterUseCase(id) }

    fun setDefault(id: String) = launchCatching("printer_set_default_failed") { setDefaultPrinterUseCase(id) }

    fun setEnabled(id: String, enabled: Boolean) =
        launchCatching("printer_set_enabled_failed") { setPrinterEnabledUseCase(id, enabled) }
}
