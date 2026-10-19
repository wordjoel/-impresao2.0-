package br.com.atos.printbridge.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Ações "fire-and-forget" disparadas por um toque (excluir, cancelar, definir padrão...) não têm
 * para onde devolver um erro na UI. Uma falha rara do Room aqui (ex.: storage cheio) não deve
 * derrubar o app silenciosamente — loga e para, sem propagar a exceção para o dispatcher da
 * coroutine (que crasharia a app) nem escondê-la completamente (fica registrada no diagnóstico).
 */
fun ViewModel.launchCatching(event: String, block: suspend () -> Unit) {
    viewModelScope.launch {
        runCatching { block() }.onFailure {
            Logger.event(LogLevel.ERROR, event, extra = mapOf("exception" to (it.message ?: it::class.simpleName)))
        }
    }
}
