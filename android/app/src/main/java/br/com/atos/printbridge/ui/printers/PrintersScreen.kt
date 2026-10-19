@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.EmptyState
import br.com.atos.printbridge.ui.theme.Spacing
import br.com.atos.printbridge.ui.theme.SuccessGreen

@Composable
fun PrintersScreen(
    onAddPrinter: () -> Unit,
    onEditPrinter: (String) -> Unit,
    onDiscover: () -> Unit = {},
    onBack: () -> Unit = {},
    savedMessage: String? = null,
    onSavedMessageShown: () -> Unit = {},
    viewModel: PrintersViewModel = hiltViewModel(),
) {
    val loaded by viewModel.printers.collectAsState()
    val printers = loaded.orEmpty()
    val status by viewModel.status.collectAsState()
    val message by viewModel.message.collectAsState()
    var printerPendingDeletion by remember { mutableStateOf<Printer?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            snackbarHostState.showSnackbar(savedMessage)
            onSavedMessageShown()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Impressoras",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onDiscover) {
                        Icon(Icons.Filled.Search, contentDescription = "Procurar impressoras na rede")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (printers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onDiscover,
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    text = { Text("Procurar") },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (printers.isEmpty()) {
                EmptyState(
                    title = "Não foram encontradas impressoras.",
                    description = "Procure na rede local (mDNS, IPP e portas de impressão) ou cadastre manualmente uma impressora de etiqueta RAW.",
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Procurar novamente")
                        }
                        OutlinedButton(onClick = onAddPrinter, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Adicionar manualmente")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = Spacing.screenPadding,
                        end = Spacing.screenPadding,
                        top = 8.dp,
                        bottom = Spacing.fabListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing),
                ) {
                    items(printers, key = { it.id }) { printer ->
                        PrinterCard(
                            printer = printer,
                            status = status[printer.id],
                            onTest = { viewModel.checkStatus(printer) },
                            onPrintTest = { viewModel.printTest(printer) },
                            onEdit = { onEditPrinter(printer.id) },
                            onSetDefault = { viewModel.setDefault(printer.id) },
                            onToggleEnabled = { viewModel.setEnabled(printer.id, it) },
                            onDelete = { printerPendingDeletion = printer },
                        )
                    }
                    item {
                        TextButton(onClick = onAddPrinter, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Adicionar manualmente (configuração manual)")
                        }
                    }
                }
            }
        }
    }

    printerPendingDeletion?.let { printer ->
        AlertDialog(
            onDismissRequest = { printerPendingDeletion = null },
            title = { Text("Remover impressora?") },
            text = { Text("\"${printer.name}\" será removida. O histórico de trabalhos já enviados não é afetado.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(printer.id)
                    printerPendingDeletion = null
                }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { printerPendingDeletion = null }) { Text("Cancelar") }
            },
        )
    }
}

/** Protocolo como a tela mostra: IPP, ou RAW com a linguagem escolhida no cadastro. */
private fun Printer.protocolText(): String = when (protocol) {
    PrinterProtocol.TCP -> when (language) {
        PrinterLanguage.AUTO -> "${protocol.label} · AUTO (tratada como ZPL)"
        else -> "${protocol.label} · ${language.name} (configuração manual)"
    }
    PrinterProtocol.IPP -> "IPP (/${ippPath.orEmpty()})"
    else -> protocol.label
}

@Composable
private fun PrinterCard(
    printer: Printer,
    status: PrinterStatusState?,
    onTest: () -> Unit,
    onPrintTest: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val report = (status as? PrinterStatusState.Done)?.report
    val statusText = when {
        status is PrinterStatusState.Checking -> "Verificando…"
        report != null -> report.status
        else -> null
    }
    val statusColor = when {
        report == null -> null
        report.online -> SuccessGreen
        else -> MaterialTheme.colorScheme.error
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = printer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (printer.isDefault) {
                        Text(
                            "Impressora padrão",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Icon(
                    imageVector = if (printer.isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = if (printer.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Mais ações")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Editar") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remover") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }

            PrinterIdentityFields(
                manufacturer = printer.displayManufacturer,
                model = printer.displayModel,
                address = printer.ipAddress,
                hostname = printer.hostname,
                port = printer.port,
                protocol = printer.protocolText(),
                status = statusText,
                statusColor = statusColor,
            )
            if (status is PrinterStatusState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            report?.detail?.let {
                Text(
                    "$it (${report.elapsedMillis} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (printer.isDefault && report != null && !report.online) {
                Text(
                    "Impressora padrão indisponível.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth(), enabled = status !is PrinterStatusState.Checking) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Testar conexão")
                }
                OutlinedButton(onClick = onPrintTest, modifier = Modifier.fillMaxWidth(), enabled = printer.enabled) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Imprimir teste")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!printer.isDefault) {
                    TextButton(onClick = onSetDefault) { Text("Definir como impressora padrão") }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (printer.enabled) "Ativa" else "Desativada",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(checked = printer.enabled, onCheckedChange = onToggleEnabled)
                }
            }
        }
    }
}
