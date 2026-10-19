@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.application.usecase.EffectiveLicenseStatus
import br.com.atos.printbridge.diagnostics.ApiTestToolActivity
import br.com.atos.printbridge.ui.brand.BrandPresets
import br.com.atos.printbridge.ui.brand.LocalBrand
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.BrandFooter
import br.com.atos.printbridge.ui.theme.ErrorRed
import br.com.atos.printbridge.ui.theme.Spacing
import br.com.atos.printbridge.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val licenseStatus by viewModel.licenseStatus.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val licenseMessage by viewModel.licenseMessage.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val brand = LocalBrand.current
    var showPinDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar(title = "Configurações", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(Spacing.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
        ) {
            // ---------------------------------------------------------------------------------
            // Operador: o que o turno precisa no dia a dia, sem senha.
            // ---------------------------------------------------------------------------------
            SectionHeader("Operação")
            Card {
                Column(modifier = Modifier.padding(Spacing.cardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Impressora padrão", style = MaterialTheme.typography.bodyMedium)
                    state.printers.forEach { printer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = printer.isDefault,
                                onClick = {
                                    // Escolher impressora padrão altera o destino físico de impressão:
                                    // permanece atrás do PIN mesmo estando na seção do operador.
                                    if (state.isAdminUnlocked) viewModel.setDefaultPrinter(printer.id) else showPinDialog = true
                                },
                            )
                            Text(
                                printer.name.ifBlank { "Impressora ${printer.ipAddress}" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Reprocessar automaticamente falhas transitórias")
                Switch(
                    checked = state.settings.autoRetryTransientFailures,
                    onCheckedChange = { v ->
                        if (state.isAdminUnlocked) viewModel.updateSettings { it.copy(autoRetryTransientFailures = v) } else showPinDialog = true
                    },
                )
            }

            OutlinedButton(onClick = onNavigateToAbout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Sobre o aplicativo")
            }

            // ---------------------------------------------------------------------------------
            // Administração: parâmetros técnicos e identidade, atrás do PIN.
            // ---------------------------------------------------------------------------------
            HorizontalDivider()
            SectionHeader("Administração (protegida)")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (state.isAdminUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = null)
                Text(if (state.isAdminUnlocked) "Desbloqueado nesta sessão" else "Bloqueado")
                TextButton(onClick = {
                    if (state.isAdminUnlocked) viewModel.lock() else showPinDialog = true
                }) { Text(if (state.isAdminUnlocked) "Bloquear" else "Desbloquear") }
            }

            // Bloqueado: o operador comum só vê o cadeado; parâmetros, histórico, marca e licença
            // aparecem (e seguem atrás do PIN) depois do desbloqueio. Nada foi removido.
            if (state.isAdminUnlocked) {
                AdminGatedNumberField(
                    label = "Número máximo de tentativas",
                    value = state.settings.maxAttempts,
                    unlocked = state.isAdminUnlocked,
                    onRequestUnlock = { showPinDialog = true },
                    onChange = { v -> viewModel.updateSettings { it.copy(maxAttempts = v) } },
                )
                AdminGatedNumberField(
                    label = "Timeout de conexão (ms)",
                    value = state.settings.connectTimeoutMillis,
                    unlocked = state.isAdminUnlocked,
                    onRequestUnlock = { showPinDialog = true },
                    onChange = { v -> viewModel.updateSettings { it.copy(connectTimeoutMillis = v) } },
                )
                AdminGatedNumberField(
                    label = "Intervalo base de retry (ms)",
                    value = state.settings.retryBaseIntervalMillis.toInt(),
                    unlocked = state.isAdminUnlocked,
                    onRequestUnlock = { showPinDialog = true },
                    onChange = { v -> viewModel.updateSettings { it.copy(retryBaseIntervalMillis = v.toLong()) } },
                )
                AdminGatedNumberField(
                    label = "Manter histórico por (dias)",
                    value = state.settings.historyRetentionDays,
                    unlocked = state.isAdminUnlocked,
                    onRequestUnlock = { showPinDialog = true },
                    onChange = { v -> viewModel.updateSettings { it.copy(historyRetentionDays = v) } },
                )
                AdminGatedNumberField(
                    label = "Porta RAW adicional na busca (além de 631/9100/515)",
                    value = state.settings.discoveryPort,
                    unlocked = state.isAdminUnlocked,
                    onRequestUnlock = { showPinDialog = true },
                    onChange = { v -> viewModel.updateSettings { it.copy(discoveryPort = v) } },
                )
                OutlinedButton(
                    onClick = {
                        if (state.isAdminUnlocked) viewModel.clearHistory() else showPinDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Limpar histórico") }

                // APK de cliente tem a marca fixa no flavor; o seletor só existe no "generico".
                if (!BuildConfig.BRAND_LOCKED) {
                    BrandSelector(
                        currentBrandId = state.brandId,
                        unlocked = state.isAdminUnlocked,
                        onRequestUnlock = { showPinDialog = true },
                        onSelect = viewModel::setBrand,
                    )
                }

                LicenseActivationCard(
                    status = licenseStatus,
                    unlocked = state.isAdminUnlocked,
                    message = licenseMessage,
                    onRequestUnlock = { showPinDialog = true },
                    onSubmit = viewModel::submitLicenseToken,
                )
            }

            // ---------------------------------------------------------------------------------
            // Diagnóstico: leitura, seguro para o operador. Nunca mostra PIN/token.
            // ---------------------------------------------------------------------------------
            HorizontalDivider()
            SectionHeader("Diagnóstico")
            Card {
                Column(modifier = Modifier.padding(Spacing.cardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 7 toques na versão (como as opções de desenvolvedor do Android) abrem a ferramenta
                    // oculta de teste da API; ela imprime e cadastra, então exige o modo administrador.
                    var versionTaps by remember { mutableIntStateOf(0) }
                    Text(
                        text = "Versão do aplicativo: ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.clickable {
                            versionTaps++
                            if (versionTaps >= 7) {
                                versionTaps = 0
                                if (state.isAdminUnlocked) {
                                    context.startActivity(Intent(context, ApiTestToolActivity::class.java))
                                } else {
                                    showPinDialog = true
                                }
                            }
                        },
                    )
                    Text("Marca ativa: ${brand.displayName}")
                    Text("Estado da rede: ${if (state.networkAvailable) "conectado" else "sem rede"}")
                    deviceId?.let {
                        Text(
                            text = "Identificador do dispositivo: $it",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "Impressora padrão: " + (
                            state.printers.firstOrNull { it.isDefault }
                                ?.let { it.name.ifBlank { "Impressora ${it.ipAddress}" } }
                                ?: "nenhuma"
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val file = viewModel.exportDiagnostics()
                            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Exportar diagnóstico"))
                        }.onFailure {
                            // Ex.: armazenamento cheio (IOException) ou nenhum app capaz de lidar com
                            // o ACTION_SEND (ActivityNotFoundException) — nunca deixa a exceção subir
                            // e derrubar a tela. Nunca it.message diretamente (poderia vazar caminho de
                            // arquivo/detalhe interno) — br.com.atos.printbridge.util.ErrorTranslator é
                            // o único ponto que decide a mensagem exibida (CARREFOUR-PRINT-OPERATOR-UX.md).
                            android.widget.Toast.makeText(
                                context,
                                "Não foi possível gerar/compartilhar o diagnóstico. Tente novamente.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Exportar logs") }

            BrandFooter()
        }
    }

    if (showPinDialog) {
        AdminPinDialog(
            isCreatingNewPin = !state.isPinConfigured,
            onDismiss = { showPinDialog = false },
            onVerify = { pin -> viewModel.tryUnlock(pin) },
            onCreate = { pin -> viewModel.setAdminPin(pin) },
        )
    }

    LaunchedEffect(licenseMessage) {
        if (licenseMessage != null) {
            delay(5000)
            viewModel.clearLicenseMessage()
        }
    }
}

@Composable
private fun LicenseActivationCard(
    status: EffectiveLicenseStatus?,
    unlocked: Boolean,
    message: String?,
    onRequestUnlock: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    SectionHeader("Licença / Ativação")
    val label = when (status) {
        null -> "Consultando…"
        EffectiveLicenseStatus.OPERATING -> "Ativa (operando)"
        EffectiveLicenseStatus.NOT_ACTIVATED -> "Não ativado"
        EffectiveLicenseStatus.RESTRICTED -> "Restrita"
        EffectiveLicenseStatus.WRONG_DEVICE -> "Bloqueada: token de outro aparelho"
    }
    val statusColor = when (status) {
        EffectiveLicenseStatus.OPERATING -> SuccessGreen
        EffectiveLicenseStatus.RESTRICTED, EffectiveLicenseStatus.WRONG_DEVICE -> ErrorRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "Status da licença: $label",
        style = MaterialTheme.typography.bodyMedium,
        color = statusColor,
    )
    Text(
        "Cole o token assinado pelo servidor de licenciamento para ativar a impressão neste dispositivo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    var tokenText by remember { mutableStateOf("") }
    OutlinedTextField(
        value = tokenText,
        onValueChange = { new ->
            if (!unlocked) {
                onRequestUnlock()
                return@OutlinedTextField
            }
            tokenText = new
        },
        label = { Text("Token de licença") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = {
            if (!unlocked) {
                onRequestUnlock()
            } else {
                onSubmit(tokenText.trim())
                tokenText = ""
            }
        },
        enabled = unlocked && tokenText.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Ativar licença") }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrandSelector(
    currentBrandId: String,
    unlocked: Boolean,
    onRequestUnlock: () -> Unit,
    onSelect: (String) -> Unit,
) {
    SectionHeader("Marca / identidade visual")
    Text(
        "Define cores, nome exibido e rodapé. Troca administrativa; o conteúdo operacional não muda.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    BrandPresets.all.forEach { preset ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = preset.brandId == currentBrandId,
                onClick = { if (unlocked) onSelect(preset.brandId) else onRequestUnlock() },
            )
            Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AdminGatedNumberField(
    label: String,
    value: Int,
    unlocked: Boolean,
    onRequestUnlock: () -> Unit,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            if (!unlocked) {
                onRequestUnlock()
                return@OutlinedTextField
            }
            text = new.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
