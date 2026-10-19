package br.com.atos.printbridge.ui.printers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.SavePrinterUseCase
import br.com.atos.printbridge.domain.error.PrintBridgeException
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrinterFormState(
    val id: String? = null,
    val storeId: String? = null,
    val name: String = "",
    val model: PrinterModel = PrinterModel.UNKNOWN,
    val ipAddress: String = "",
    // Sem porta nem linguagem presumidas: vêm da descoberta (argumentos) ou são digitadas.
    val port: String = "",
    val language: PrinterLanguage = PrinterLanguage.AUTO,
    val protocol: PrinterProtocol = PrinterProtocol.TCP,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val dpi: Int = Printer.DEFAULT_DPI,
    val labelWidthMm: String = Printer.DEFAULT_LABEL_WIDTH_MM.toString(),
    val labelHeightMm: String = Printer.DEFAULT_LABEL_HEIGHT_MM.toString(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class PrinterFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val savePrinterUseCase: SavePrinterUseCase,
    private val printerRepository: PrinterRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PrinterFormState())
    val state: StateFlow<PrinterFormState> = _state.asStateFlow()

    init {
        val printerId: String? = savedStateHandle["printerId"]
        val prefillIp: String? = savedStateHandle["ip"]
        val prefillPort: String? = savedStateHandle["port"]
        if (printerId.isNullOrBlank() && !prefillIp.isNullOrBlank()) {
            val prefillModel = savedStateHandle.get<String>("model")?.let { m -> PrinterModel.entries.firstOrNull { it.name == m } }
            _state.value = PrinterFormState(ipAddress = prefillIp, port = prefillPort.orEmpty(), model = prefillModel ?: PrinterModel.UNKNOWN)
        }
        if (!printerId.isNullOrBlank()) {
            viewModelScope.launch {
                printerRepository.getById(printerId)?.let { p ->
                    _state.value = PrinterFormState(
                        id = p.id,
                        storeId = p.storeId,
                        name = p.name,
                        model = p.model,
                        ipAddress = p.ipAddress,
                        port = p.port.toString(),
                        language = p.language,
                        protocol = p.protocol,
                        isDefault = p.isDefault,
                        enabled = p.enabled,
                        dpi = p.dpi,
                        labelWidthMm = p.labelWidthMm.toString(),
                        labelHeightMm = p.labelHeightMm.toString(),
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) { _state.value = _state.value.copy(name = value) }
    fun onModelChange(value: PrinterModel) { _state.value = _state.value.copy(model = value) }
    fun onIpChange(value: String) { _state.value = _state.value.copy(ipAddress = value) }
    fun onPortChange(value: String) { _state.value = _state.value.copy(port = value.filter { it.isDigit() }) }
    fun onLanguageChange(value: PrinterLanguage) { _state.value = _state.value.copy(language = value) }
    fun onDefaultChange(value: Boolean) { _state.value = _state.value.copy(isDefault = value) }
    fun onEnabledChange(value: Boolean) { _state.value = _state.value.copy(enabled = value) }
    fun onDpiChange(value: Int) { _state.value = _state.value.copy(dpi = value) }
    fun onLabelWidthChange(value: String) { _state.value = _state.value.copy(labelWidthMm = value.filter { it.isDigit() }) }
    fun onLabelHeightChange(value: String) { _state.value = _state.value.copy(labelHeightMm = value.filter { it.isDigit() }) }

    fun save() {
        val current = _state.value
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true, errorMessage = null)
            runCatching {
                savePrinterUseCase(
                    id = current.id,
                    storeId = current.storeId,
                    name = current.name,
                    model = current.model,
                    ipAddress = current.ipAddress,
                    port = current.port.toIntOrNull() ?: -1,
                    language = current.language,
                    isDefault = current.isDefault,
                    enabled = current.enabled,
                    dpi = current.dpi,
                    labelWidthMm = current.labelWidthMm.toIntOrNull() ?: -1,
                    labelHeightMm = current.labelHeightMm.toIntOrNull() ?: -1,
                    protocol = current.protocol,
                )
            }.onSuccess {
                _state.value = _state.value.copy(isLoading = false, saved = true)
            }.onFailure { e ->
                val message = if (e is PrintBridgeException) e.message else "Falha ao salvar impressora"
                _state.value = _state.value.copy(isLoading = false, errorMessage = message)
            }
        }
    }
}
