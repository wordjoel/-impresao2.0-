package br.com.atos.printbridge.debug

import androidx.activity.ComponentActivity
import android.os.Bundle
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.StoreLicenseTokenUseCase
import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Build DEBUG apenas: grava um token de licença de laboratório enviado por ADB (ver
 * src/debug/AndroidManifest.xml). Mesmo caminho da tela de Configurações (StoreLicenseTokenUseCase),
 * só sem digitação no coletor. O token continua sendo verificado pela assinatura — um token
 * inválido resulta em RESTRICTED, igual à tela. O resultado sai no logcat
 * (evento lab_license_provisioned), que é o que o script de ativação lê.
 */
@AndroidEntryPoint
class LabProvisioningActivity : ComponentActivity() {

    @Inject lateinit var storeLicenseToken: StoreLicenseTokenUseCase
    @Inject lateinit var evaluateLicenseStatus: EvaluateLicenseStatusUseCase
    @Inject lateinit var deviceIdentity: DeviceIdentityProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token")?.trim()
        // deviceId vai no evento para o script gerar o token de laboratório PARA ESTE aparelho:
        // a licença só vale no aparelho cujo deviceId está assinado nela (anti-clonagem).
        val deviceId = runCatching { deviceIdentity.deviceId() }.getOrDefault("")
        val status = if (token.isNullOrEmpty()) {
            "SEM_TOKEN"
        } else {
            // Operação curta (DataStore + verificação ECDSA); a Activity é invisível e fecha em seguida.
            runBlocking {
                storeLicenseToken(token)
                evaluateLicenseStatus().name
            }
        }
        Logger.event(LogLevel.INFO, "lab_license_provisioned", extra = mapOf("status" to status, "deviceId" to deviceId))
        finish()
    }
}
