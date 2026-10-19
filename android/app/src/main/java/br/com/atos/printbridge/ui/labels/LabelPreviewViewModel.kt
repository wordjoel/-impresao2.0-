package br.com.atos.printbridge.ui.labels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.GetLabelTemplateUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.PrintLabelUseCase
import br.com.atos.printbridge.application.usecase.RenderLabelZplUseCase
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.util.ErrorTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LabelPreviewUiState(
    val template: LabelTemplate? = null,
    val data: LabelData = LabelData.sample(),
    val printers: List<Printer> = emptyList(),
    val selectedPrinterId: String? = null,
    val copies: Int = 1,
    val zpl: String = "",
    val showZpl: Boolean = false,
    val isPrinting: Boolean = false,
    val isLoading: Boolean = true,
    val feedback: String? = null,
)

@HiltViewModel
class LabelPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getLabelTemplate: GetLabelTemplateUseCase,
    observePrinters: ObservePrintersUseCase,
    private val renderLabelZpl: RenderLabelZplUseCase,
    private val printLabel: PrintLabelUseCase,
) : ViewModel() {

    private val templateId: String? = savedStateHandle["templateId"]

    private val _state = MutableStateFlow(LabelPreviewUiState())
    val state: StateFlow<LabelPreviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observePrinters().collect { printers ->
                val current = _state.value
                val stillValid = printers.any { it.id == current.selectedPrinterId }
                _state.value = current.copy(
                    printers = printers,
                    selectedPrinterId = when {
                        stillValid -> current.selectedPrinterId
                        else -> printers.firstOrNull { it.isDefault }?.id ?: printers.firstOrNull()?.id
                    },
                )
            }
        }
        loadTemplate()
    }

    private fun loadTemplate() {
        val id = templateId
        if (id.isNullOrBlank()) {
            _state.value = _state.value.copy(isLoading = false, feedback = "Nenhum modelo informado.")
            return
        }
        viewModelScope.launch {
            runCatching { getLabelTemplate(id) }
                .onSuccess { template ->
                    _state.value = _state.value.copy(
                        template = template,
                        zpl = renderLabelZpl(template, _state.value.data),
                        isLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false, feedback = ErrorTranslator.translate(it))
                }
        }
    }

    fun selectPrinter(id: String?) {
        _state.value = _state.value.copy(selectedPrinterId = id)
    }

    fun changeCopies(delta: Int) {
        _state.value = _state.value.copy(copies = (_state.value.copies + delta).coerceIn(1, 100))
    }

    fun toggleZpl() {
        _state.value = _state.value.copy(showZpl = !_state.value.showZpl)
    }

    fun print() {
        val template = _state.value.template ?: return
        val current = _state.value
        viewModelScope.launch {
            _state.value = current.copy(isPrinting = true)
            runCatching {
                printLabel.printQuantity(
                    template = template,
                    data = current.data,
                    printerId = current.selectedPrinterId,
                    quantity = current.copies,
                )
            }.onSuccess { jobs ->
                val sent = if (jobs.size == 1) {
                    "Etiqueta enviada para a fila (job ${jobs.first().id.take(8)})."
                } else {
                    "${jobs.size} etiquetas enviadas para a fila, uma por job."
                }
                _state.value = _state.value.copy(
                    isPrinting = false,
                    feedback = "$sent A impressão é confirmada pelo resultado do job — o envio à impressora não é prova de impressão física.",
                )
            }.onFailure {
                _state.value = _state.value.copy(isPrinting = false, feedback = ErrorTranslator.translate(it))
            }
        }
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(feedback = null)
    }
}
