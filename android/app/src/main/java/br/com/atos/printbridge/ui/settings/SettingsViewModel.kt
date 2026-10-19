package br.com.atos.printbridge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.application.usecase.EffectiveLicenseStatus
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.IsAdminPinConfiguredUseCase
import br.com.atos.printbridge.application.usecase.ObservePrintersUseCase
import br.com.atos.printbridge.application.usecase.SetAdminPinUseCase
import br.com.atos.printbridge.application.usecase.SetDefaultPrinterUseCase
import br.com.atos.printbridge.application.usecase.StoreLicenseTokenUseCase
import br.com.atos.printbridge.application.usecase.VerifyAdminPinUseCase
import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.model.AppSettings
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.network.NetworkStateProvider
import br.com.atos.printbridge.domain.repository.BrandRepository
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.ui.brand.DEFAULT_BRAND_ID
import br.com.atos.printbridge.util.DiagnosticsExporter
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import br.com.atos.printbridge.util.launchCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val printers: List<Printer> = emptyList(),
    val isAdminUnlocked: Boolean = false,
    val isPinConfigured: Boolean = false,
    val networkAvailable: Boolean = true,
    val brandId: String = DEFAULT_BRAND_ID.key,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    observePrintersUseCase: ObservePrintersUseCase,
    private val setDefaultPrinterUseCase: SetDefaultPrinterUseCase,
    private val setAdminPinUseCase: SetAdminPinUseCase,
    private val verifyAdminPinUseCase: VerifyAdminPinUseCase,
    private val isAdminPinConfiguredUseCase: IsAdminPinConfiguredUseCase,
    private val printJobRepository: PrintJobRepository,
    private val diagnosticsExporter: DiagnosticsExporter,
    private val networkStateProvider: NetworkStateProvider,
    private val brandRepository: BrandRepository,
    private val evaluateLicenseStatusUseCase: EvaluateLicenseStatusUseCase,
    private val storeLicenseTokenUseCase: StoreLicenseTokenUseCase,
    deviceIdentityProvider: DeviceIdentityProvider,
) : ViewModel() {

    private val _isAdminUnlocked = MutableStateFlow(false)
    private val _isPinConfigured = MutableStateFlow(false)
    private val _licenseStatus = MutableStateFlow<EffectiveLicenseStatus?>(null)
    private val _deviceId = MutableStateFlow<String?>(null)
    private val _licenseMessage = MutableStateFlow<String?>(null)

    val licenseStatus: StateFlow<EffectiveLicenseStatus?> = _licenseStatus.asStateFlow()
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()
    val licenseMessage: StateFlow<String?> = _licenseMessage.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.observe(),
        observePrintersUseCase(),
        _isAdminUnlocked,
        _isPinConfigured,
        brandRepository.observeBrandKey(),
    ) { settings, printers, unlocked, pinConfigured, brandKey ->
        SettingsUiState(
            settings = settings,
            printers = printers,
            isAdminUnlocked = unlocked,
            isPinConfigured = pinConfigured,
            networkAvailable = networkStateProvider.isNetworkAvailable(),
            brandId = brandKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            _isPinConfigured.value = isAdminPinConfiguredUseCase()
            _licenseStatus.value = evaluateLicenseStatusUseCase()
            _deviceId.value = runCatching { deviceIdentityProvider.deviceId() }.getOrNull()
        }
    }

    suspend fun tryUnlock(pin: String): Boolean {
        val ok = verifyAdminPinUseCase(pin)
        if (ok) _isAdminUnlocked.value = true
        return ok
    }

    fun lock() {
        _isAdminUnlocked.value = false
    }

    fun setAdminPin(pin: String) = launchCatching("settings_set_admin_pin_failed") {
        setAdminPinUseCase(pin)
        _isPinConfigured.value = true
        _isAdminUnlocked.value = true
    }

    fun setDefaultPrinter(printerId: String) =
        launchCatching("settings_set_default_printer_failed") { setDefaultPrinterUseCase(printerId) }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = launchCatching("settings_update_failed") {
        settingsRepository.update(transform)
    }

    fun clearHistory() = launchCatching("settings_clear_history_failed") {
        printJobRepository.purgeHistoryOlderThan(Long.MAX_VALUE)
    }

    /** Troca de marca (identidade visual). Ação administrativa; o gate fica na tela. */
    fun setBrand(brandKey: String) = launchCatching("settings_set_brand_failed") {
        brandRepository.setBrandKey(brandKey)
    }

    fun refreshLicense() = launchCatching("settings_refresh_license_failed") {
        _licenseStatus.value = evaluateLicenseStatusUseCase()
    }

    /**
     * Ativação local: armazena o token assinado pelo servidor de licenciamento e reavalia o status
     * com o verificador embutido. O token é a ÚNICA fonte de verdade (nunca campos editáveis);
     * tokens inválidos caem em RESTRICTED, nunca reabrem a tela como "não ativado".
     */
    fun submitLicenseToken(rawToken: String) {
        viewModelScope.launch {
            runCatching {
                storeLicenseTokenUseCase(rawToken)
                evaluateLicenseStatusUseCase()
            }.onSuccess { status ->
                _licenseStatus.value = status
                _licenseMessage.value = when (status) {
                    EffectiveLicenseStatus.OPERATING -> "Dispositivo ativado com sucesso."
                    EffectiveLicenseStatus.WRONG_DEVICE -> "Este token foi emitido para outro aparelho e não vale aqui."
                    else -> "Token reconhecido, mas o status é restrito ou expirado."
                }
            }.onFailure {
                Logger.event(LogLevel.ERROR, "settings_store_license_token_failed", extra = mapOf("exception" to (it.message ?: it::class.simpleName)))
                _licenseMessage.value = "Não foi possível salvar o token. Tente novamente."
            }
        }
    }

    fun clearLicenseMessage() {
        _licenseMessage.value = null
    }

    suspend fun exportDiagnostics(): File {
        val settings = settingsRepository.current()
        return diagnosticsExporter.export(settings, networkStateProvider.isNetworkAvailable())
    }
}
