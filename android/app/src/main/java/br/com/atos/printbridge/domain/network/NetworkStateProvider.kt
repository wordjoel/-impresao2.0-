package br.com.atos.printbridge.domain.network

interface NetworkStateProvider {
    fun isNetworkAvailable(): Boolean
}
