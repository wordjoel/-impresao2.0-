package br.com.atos.printbridge.ui.labels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.GetLabelTemplateUseCase
import br.com.atos.printbridge.application.usecase.SaveLabelTemplateUseCase
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.util.ErrorTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LabelEditorUiState(
    val templateId: String? = null,
    val isNew: Boolean = true,
    val name: String = "Nova etiqueta",
    val widthMm: Int = 40,
    val heightMm: Int = 30,
    val dpi: Int = LabelTemplate.DEFAULT_DPI,
    val elements: List<LabelElement> = emptyList(),
    val selectedElementId: String? = null,
    val isBuiltIn: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val isLoading: Boolean = true,
    val feedback: String? = null,
    val savedAndExit: Boolean = false,
) {
    val selectedElement: LabelElement?
        get() = elements.firstOrNull { it.id == selectedElementId }

    fun toTemplate(): LabelTemplate = LabelTemplate(
        id = templateId ?: UUID.randomUUID().toString(),
        name = name,
        widthMm = widthMm,
        heightMm = heightMm,
        dpi = dpi,
        elements = elements,
        isBuiltIn = isBuiltIn,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = 0L,
    )

    companion object {
        fun from(template: LabelTemplate) = LabelEditorUiState(
            templateId = template.id,
            isNew = false,
            name = template.name,
            widthMm = template.widthMm,
            heightMm = template.heightMm,
            dpi = template.dpi,
            elements = template.elements,
            selectedElementId = template.elements.firstOrNull()?.id,
            isBuiltIn = template.isBuiltIn,
            isDefault = template.isDefault,
            createdAt = template.createdAt,
            isLoading = false,
        )
    }
}

@HiltViewModel
class LabelEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getLabelTemplate: GetLabelTemplateUseCase,
    private val saveLabelTemplate: SaveLabelTemplateUseCase,
) : ViewModel() {

    private val requestedId: String? = savedStateHandle["templateId"]

    private val _state = MutableStateFlow(LabelEditorUiState())
    val state: StateFlow<LabelEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (requestedId.isNullOrBlank()) {
                _state.value = _state.value.copy(isLoading = false)
            } else {
                runCatching { getLabelTemplate(requestedId) }
                    .onSuccess { _state.value = LabelEditorUiState.from(it) }
                    .onFailure {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            feedback = ErrorTranslator.translate(it),
                        )
                    }
            }
        }
    }

    fun onNameChange(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun onWidthChange(delta: Int) = _state.value.let { current ->
        val value = (current.widthMm + delta).coerceIn(LabelTemplate.MIN_MM, LabelTemplate.MAX_MM)
        _state.value = current.copy(widthMm = value, elements = current.elements.map { it.clampTo(value, current.heightMm) })
    }

    fun onHeightChange(delta: Int) = _state.value.let { current ->
        val value = (current.heightMm + delta).coerceIn(LabelTemplate.MIN_MM, LabelTemplate.MAX_MM)
        _state.value = current.copy(heightMm = value, elements = current.elements.map { it.clampTo(current.widthMm, value) })
    }

    fun onDpiChange(dpi: Int) {
        _state.value = _state.value.copy(dpi = dpi)
    }

    fun selectElement(id: String?) {
        _state.value = _state.value.copy(selectedElementId = id)
    }

    fun addElement(type: LabelElementType) {
        val current = _state.value
        val width = type.defaultWidthMm.coerceAtMost(current.widthMm.toFloat())
        val height = type.defaultHeightMm.coerceAtMost(current.heightMm.toFloat())
        val element = LabelElement(
            id = UUID.randomUUID().toString(),
            type = type,
            xMm = 1f,
            yMm = 1f,
            widthMm = width,
            heightMm = height,
            field = if (type == LabelElementType.CAMPO || type == LabelElementType.CODIGO_BARRAS || type == LabelElementType.QRCODE) {
                br.com.atos.printbridge.domain.model.label.LabelField.PRODUTO
            } else {
                null
            },
            text = if (type == LabelElementType.TEXTO) "Texto" else "",
        )
        _state.value = current.copy(
            elements = current.elements + element,
            selectedElementId = element.id,
        )
    }

    fun moveElement(id: String, dxMm: Float, dyMm: Float) {
        updateElement(id) { element ->
            val maxX = (_state.value.widthMm - element.widthMm).coerceAtLeast(0f)
            val maxY = (_state.value.heightMm - element.heightMm).coerceAtLeast(0f)
            element.copy(
                xMm = (element.xMm + dxMm).coerceIn(0f, maxX),
                yMm = (element.yMm + dyMm).coerceIn(0f, maxY),
            )
        }
    }

    fun updateElement(id: String, transform: (LabelElement) -> LabelElement) {
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { if (it.id == id) transform(it) else it },
        )
    }

    fun nudgeElement(id: String, dXMm: Float, dYMm: Float) = moveElement(id, dXMm, dYMm)

    fun resizeElement(id: String, dWidthMm: Float, dHeightMm: Float) {
        updateElement(id) { element ->
            val width = (element.widthMm + dWidthMm).coerceIn(1f, _state.value.widthMm.toFloat())
            val height = (element.heightMm + dHeightMm).coerceIn(1f, _state.value.heightMm.toFloat())
            element.copy(widthMm = width, heightMm = height).clampTo(_state.value.widthMm, _state.value.heightMm)
        }
    }

    fun setFontSize(id: String, deltaMm: Float) = updateElement(id) { element ->
        element.copy(
            fontSizeMm = (element.fontSizeMm + deltaMm)
                .coerceIn(LabelElement.MIN_FONT_MM, LabelElement.MAX_FONT_MM),
        )
    }

    fun setThickness(id: String, deltaMm: Float) = updateElement(id) { element ->
        element.copy(
            thicknessMm = (element.thicknessMm + deltaMm)
                .coerceIn(LabelElement.MIN_THICKNESS_MM, LabelElement.MAX_THICKNESS_MM),
        )
    }

    fun setRotation(id: String, degrees: Int) = updateElement(id) { it.copy(rotationDeg = degrees) }

    fun setAlign(id: String, align: br.com.atos.printbridge.domain.model.label.LabelAlign) =
        updateElement(id) { it.copy(align = align) }

    fun setField(id: String, field: br.com.atos.printbridge.domain.model.label.LabelField) =
        updateElement(id) { it.copy(field = field) }

    fun setBarcodeType(id: String, barcodeType: br.com.atos.printbridge.domain.model.label.BarcodeType) =
        updateElement(id) { it.copy(barcodeType = barcodeType) }

    fun setText(id: String, text: String) = updateElement(id) { it.copy(text = text) }

    fun deleteElement(id: String) {
        _state.value = _state.value.copy(
            elements = _state.value.elements.filterNot { it.id == id },
            selectedElementId = _state.value.selectedElementId?.takeIf { it != id },
        )
    }

    fun save() {
        val current = _state.value
        viewModelScope.launch {
            runCatching { saveLabelTemplate(current.toTemplate()) }
                .onSuccess {
                    _state.value = _state.value.copy(feedback = "Modelo salvo.", savedAndExit = true)
                }
                .onFailure {
                    _state.value = _state.value.copy(feedback = ErrorTranslator.translate(it))
                }
        }
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(feedback = null)
    }
}

/** Mantém o elemento dentro dos limites quando largura/altura da etiqueta mudam no editor. */
private fun LabelElement.clampTo(labelWidthMm: Int, labelHeightMm: Int): LabelElement {
    val width = widthMm.coerceAtMost(labelWidthMm.toFloat())
    val height = heightMm.coerceAtMost(labelHeightMm.toFloat())
    return copy(
        widthMm = width,
        heightMm = height,
        xMm = xMm.coerceIn(0f, (labelWidthMm - width).coerceAtLeast(0f)),
        yMm = yMm.coerceIn(0f, (labelHeightMm - height).coerceAtLeast(0f)),
    )
}
