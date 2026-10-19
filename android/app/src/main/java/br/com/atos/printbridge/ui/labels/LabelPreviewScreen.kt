package br.com.atos.printbridge.ui.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.ErrorState
import br.com.atos.printbridge.ui.common.LoadingState
import br.com.atos.printbridge.ui.theme.Spacing

/**
 * Pré-visualização + envio: mostra o layout em escala (mesmo [LabelCanvas] do editor, somente
 * leitura), a impressora de destino e o ZPL que será enviado. O botão de imprimir entrega o job à
 * fila local — a mensagem de retorno é deliberadamente "enviada para a fila", nunca "impressa":
 * o app não tem confirmação física da TSC (`SENT` ≠ `PRINTED`).
 */
@Composable
fun LabelPreviewScreen(
    onBack: () -> Unit,
    viewModel: LabelPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Pré-visualização", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val template = state.template
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            template == null -> ErrorState(
                message = "Não foi possível abrir este modelo de etiqueta.",
                modifier = Modifier.padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${template.widthMm} × ${template.heightMm} mm · ${template.dpi} dpi · " +
                            if (template.elements.size == 1) "1 elemento" else "${template.elements.size} elementos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LabelCanvas(
                    template = template,
                    data = state.data,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Os valores exibidos são exemplos de preenchimento. O conteúdo real de cada campo é definido no momento da impressão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PrinterSelector(
                    printers = state.printers,
                    selectedPrinterId = state.selectedPrinterId,
                    onSelect = viewModel::selectPrinter,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantidade de etiquetas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { viewModel.changeCopies(-1) },
                        enabled = state.copies > 1,
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Diminuir quantidade")
                    }
                    Text(
                        state.copies.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(
                        onClick = { viewModel.changeCopies(1) },
                        enabled = state.copies < 100,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Aumentar quantidade")
                    }
                }

                Button(
                    onClick = viewModel::print,
                    enabled = !state.isPrinting && state.printers.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.isPrinting) "Enviando..." else "Enfileirar impressão")
                }

                if (state.printers.isEmpty()) {
                    Text(
                        "Nenhuma impressora cadastrada. Cadastre uma em Impressoras para poder imprimir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                TextButton(onClick = viewModel::toggleZpl) {
                    Text(if (state.showZpl) "Ocultar ZPL gerado" else "Ver ZPL gerado")
                }

                if (state.showZpl) {
                    Text(
                        "${state.zpl.toByteArray(Charsets.UTF_8).size} bytes · ${template.dots(template.widthMm)} × ${template.dots(template.heightMm)} dots",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                text = state.zpl,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .padding(Spacing.cardInnerPadding),
                            )
                        }
                    }
                }

                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PrinterSelector(
    printers: List<br.com.atos.printbridge.domain.model.Printer>,
    selectedPrinterId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = printers.firstOrNull { it.id == selectedPrinterId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Impressora", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = printers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected?.let { it.name.ifBlank { it.ipAddress } } ?: "Sem impressora",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                printers.forEach { printer ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                printer.name.ifBlank { printer.ipAddress } +
                                    (if (printer.isDefault) " (padrão)" else "") +
                                    " · ${printer.ipAddress}:${printer.port}",
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(printer.id)
                        },
                    )
                }
            }
        }
    }
}
