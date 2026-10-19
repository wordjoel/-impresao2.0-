package br.com.atos.printbridge.ui.brand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.atos.printbridge.domain.repository.BrandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Marca ativa observada na raiz do app (MainActivity). A troca em si é feita em
 * Configurações → Administração via [br.com.atos.printbridge.ui.settings.SettingsViewModel];
 * como as duas pontas observam o mesmo DataStore, o tema recompõe sozinho, sem ViewModel
 * compartilhado entre donos de ViewModelStore diferentes.
 */
@HiltViewModel
class BrandViewModel @Inject constructor(
    brandRepository: BrandRepository,
) : ViewModel() {

    val brand: StateFlow<BrandConfig> = brandRepository.observeBrandKey()
        .map { BrandPresets.current(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrandPresets.current(null))
}
