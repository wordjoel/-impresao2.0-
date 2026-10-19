package br.com.atos.printbridge.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.DeleteLabelTemplateUseCase
import br.com.atos.printbridge.application.usecase.DuplicateLabelTemplateUseCase
import br.com.atos.printbridge.application.usecase.EnsureBuiltInLabelsUseCase
import br.com.atos.printbridge.application.usecase.ObserveLabelTemplatesUseCase
import br.com.atos.printbridge.application.usecase.SetDefaultLabelTemplateUseCase
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.util.ErrorTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LabelsUiState(
    val templates: List<LabelTemplate> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class LabelsViewModel @Inject constructor(
    observeLabelTemplates: ObserveLabelTemplatesUseCase,
    private val ensureBuiltInLabels: EnsureBuiltInLabelsUseCase,
    private val setDefaultLabelTemplate: SetDefaultLabelTemplateUseCase,
    private val duplicateLabelTemplate: DuplicateLabelTemplateUseCase,
    private val deleteLabelTemplate: DeleteLabelTemplateUseCase,
) : ViewModel() {

    val uiState: StateFlow<LabelsUiState> = observeLabelTemplates()
        .map { LabelsUiState(templates = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabelsUiState())

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    init {
        // Semeia os modelos nativos só na primeira vez (tabela vazia).
        viewModelScope.launch { runCatching { ensureBuiltInLabels() } }
    }

    fun setDefault(id: String) = run("Modelo padrão atualizado.") { setDefaultLabelTemplate(id) }

    fun duplicate(id: String) = run("Modelo duplicado.") { duplicateLabelTemplate(id) }

    fun delete(id: String) = run("Modelo removido.") { deleteLabelTemplate(id) }

    fun clearFeedback() {
        _feedback.value = null
    }

    private fun run(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _feedback.value = successMessage }
                .onFailure { _feedback.value = ErrorTranslator.translate(it) }
        }
    }
}
