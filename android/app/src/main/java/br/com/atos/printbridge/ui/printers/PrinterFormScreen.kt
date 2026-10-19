package br.com.atos.printbridge.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.theme.Spacing

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrinterFormScreen(
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: PrinterFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val name = state.name.trim()
            onSaved(if (name.isEmpty()) "Impressora salva" else "Impressora \"$name\" salva")
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (state.id == null) "Nova impressora" else "Editar impressora",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(Spacing.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
        ) {
            Text(
                text = if (state.protocol == PrinterProtocol.IPP) {
                    "Impressora IPP: fabricante, modelo, papel e resolução vêm da própria impressora (Testar conexão atualiza). Aqui só nome, endereço e status."
                } else {
                    "Configuração manual (RAW): os dados abaixo são digitados, não descobertos. Impressoras de escritório (IPP) devem ser adicionadas por Procurar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Nome") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (state.protocol != PrinterProtocol.IPP) EnumDropdown(
                label = "Modelo",
                options = PrinterModel.entries,
                selected = state.model,
                optionLabel = { it.label },
                onSelected = viewModel::onModelChange,
            )

            OutlinedTextField(
                value = state.ipAddress,
                onValueChange = viewModel::onIpChange,
                label = { Text("Endereço IP") },
                supportingText = { Text("IP da impressora na mesma rede do Android") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::onPortChange,
                label = { Text("Porta") },
                supportingText = { Text(if (state.protocol == PrinterProtocol.IPP) "Porta do serviço IPP descoberta" else "Porta RAW da impressora (veja o relatório de configuração dela)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (state.protocol != PrinterProtocol.IPP) {
            EnumDropdown(
                label = "Linguagem",
                options = PrinterLanguage.entries,
                selected = state.language,
                optionLabel = { if (it == PrinterLanguage.AUTO) "Não informado (escolha ZPL ou TSPL)" else it.name },
                onSelected = viewModel::onLanguageChange,
            )

            // Perfil de mídia: usado para rasterizar PDF/imagem no tamanho e resolução certos.
            EnumDropdown(
                label = "Resolução (DPI)",
                options = Printer.SUPPORTED_DPI,
                selected = state.dpi,
                optionLabel = { "$it dpi" },
                onSelected = viewModel::onDpiChange,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.labelWidthMm,
                    onValueChange = viewModel::onLabelWidthChange,
                    label = { Text("Largura papel (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.labelHeightMm,
                    onValueChange = viewModel::onLabelHeightChange,
                    label = { Text("Altura papel (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Impressora padrão", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.isDefault, onCheckedChange = viewModel::onDefaultChange)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ativa", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.enabled, onCheckedChange = viewModel::onEnabledChange)
            }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = viewModel::save, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isLoading) "Salvando…" else "Salvar")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
