package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.security.PinHasher
import javax.inject.Inject

class SetAdminPinUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) {
            "PIN deve ter entre 4 e 8 dígitos numéricos"
        }
        val hashed = PinHasher.hash(pin)
        settingsRepository.update { it.copy(adminPinHash = hashed) }
    }
}

class VerifyAdminPinUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(pin: String): Boolean {
        val stored = settingsRepository.current().adminPinHash ?: return false
        return PinHasher.verify(pin, stored)
    }
}

class IsAdminPinConfiguredUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(): Boolean = settingsRepository.current().adminPinHash != null
}
