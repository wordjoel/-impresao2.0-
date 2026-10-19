@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.domain.discovery.DiscoveredPrinter
import br.com.atos.printbridge.domain.discovery.DiscoveredService
import br.com.atos.printbridge.domain.discovery.RegisteredPrinterStatus
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.EmptyState
import br.com.atos.printbridge.ui.theme.Spacing
import br.com.atos.printbridge.ui.theme.SuccessGreen

@Composable
fun PrinterDiscoveryScreen(
    onBack: () -> Unit,
    onConfigureManually: (ip: String, port: Int, model: String?) -> Unit = { _, _, _ -> },
    viewModel: PrinterDiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Procurar impressoras", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = Spacing.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    !state.networkAvailable -> "Android sem rede — conecte ao Wi-Fi da impressora."
                    state.localNetwork != null -> "Rede do Android: ${state.localNetwork} · mDNS + IPP + portas 631/9100/515"
                    else -> "Android sem endereço IPv4 — só o mDNS pode encontrar impressoras."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.networkAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            val listing = state.listing
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Procurando… ${state.results.size} host(s) com serviço de impressão",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = viewModel::stop) { Text("Parar") }
                } else if (state.finished) {
                    Text(
                        "${state.results.size} host(s) encontrado(s)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = viewModel::start) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Procurar novamente")
                    }
                }
            }

            if (state.finished && listing.registered.isEmpty() && listing.newOnNetwork.isEmpty()) {
                EmptyState(
                    title = "Não foram encontradas impressoras na rede.",
                    description = noNewPrintersText(state.localNetwork),
                ) {
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Procurar novamente")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (listing.registered.isNotEmpty()) {
                        item(key = "h-registered") { SectionTitle("Cadastradas neste aparelho (${listing.registered.size})") }
                        items(listing.registered, key = { "r-${it.printer.id}" }) { status ->
                            RegisteredPrinterCard(status = status, scanning = state.isScanning)
                        }
                    }
                    item(key = "h-new") { SectionTitle("Novas na rede (${listing.newOnNetwork.size})") }
                    if (state.finished && listing.newOnNetwork.isEmpty()) {
                        item(key = "none-new") {
                            Text(noNewPrintersText(state.localNetwork), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(listing.newOnNetwork, key = { "n-${it.dedupeKey}" }) { printer ->
                        DiscoveredPrinterCard(
                            printer = printer,
                            onSave = { viewModel.save(printer) },
                            onConfigureManually = onConfigureManually,
                        )
                    }
                }
            }
        }
    }
}

private fun noNewPrintersText(localNetwork: String?) =
    "Nenhum anúncio mDNS de impressora e nenhuma porta 631/9100/515 aberta na rede ${localNetwork ?: "atual"}. " +
        "Confira se a impressora está ligada e no mesmo Wi-Fi do Android (Wi-Fi Direct da impressora é outra rede)."

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

/** Cadastrada: sempre aparece; a busca só diz se ela respondeu agora. Nunca oferece "Adicionar". */
@Composable
private fun RegisteredPrinterCard(status: RegisteredPrinterStatus, scanning: Boolean) {
    val printer = status.printer
    val (situation, color) = when {
        status.responding -> "Respondendo nesta busca" to SuccessGreen
        scanning -> "Verificando…" to null
        else -> "Não respondeu nesta busca" to MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(printer.name, style = MaterialTheme.typography.titleMedium)
            PrinterIdentityFields(
                manufacturer = printer.displayManufacturer,
                model = printer.displayModel,
                address = printer.ipAddress,
                hostname = printer.hostname,
                port = printer.port,
                protocol = printer.protocol.label,
                status = if (printer.enabled) situation else "$situation · Desativada",
                statusColor = color,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Done, contentDescription = null, tint = SuccessGreen)
                Spacer(Modifier.size(6.dp))
                Text(if (printer.isDefault) "Cadastrada · padrão" else "Cadastrada", color = SuccessGreen)
            }
        }
    }
}

@Composable
private fun DiscoveredPrinterCard(
    printer: DiscoveredPrinter,
    onSave: () -> Unit,
    onConfigureManually: (String, Int, String?) -> Unit,
) {
    val preferred = printer.preferredService
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = printer.name ?: listOfNotNull(printer.manufacturer, printer.model).joinToString(" ").ifBlank { printer.ipAddress },
                style = MaterialTheme.typography.titleMedium,
            )
            PrinterIdentityFields(
                manufacturer = printer.manufacturer,
                model = printer.model,
                address = printer.ipAddress,
                hostname = printer.hostname,
                port = preferred?.port,
                protocol = preferred?.protocol?.label,
                status = printer.state?.let { "Respondendo · $it" } ?: if (preferred != null) "Respondendo" else null,
                statusColor = if (preferred != null) SuccessGreen else null,
            )
            printer.identitySource?.let {
                Text("Identificação informada pela impressora via $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (printer.stateReasons.isNotEmpty()) {
                Text("Avisos da impressora: ${printer.stateReasons.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            printer.capabilities?.let { caps ->
                PrinterField("Formatos", caps.documentFormats.joinToString().ifBlank { null })
                PrinterField("Resolução", caps.resolutionsDpi.joinToString(" / ") { "$it dpi" }.ifBlank { null })
                PrinterField("Papel", caps.defaultMedia)
                PrinterField("Duplex", if (caps.sides.isEmpty()) null else if (caps.supportsDuplex) "Sim" else "Não")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            Text("Serviços encontrados", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            printer.services.sortedBy { it.port }.forEach { ServiceLine(it) }

            printer.problem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                when {
                    preferred != null -> Button(onClick = onSave) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Adicionar")
                    }
                    printer.unverifiedRaw != null -> OutlinedButton(onClick = { onConfigureManually(
                        printer.ipAddress,
                        printer.unverifiedRaw!!.port,
                        br.com.atos.printbridge.printing.discovery.NsdServiceMapper.modelHint(listOfNotNull(printer.manufacturer, printer.model, printer.name).joinToString(" "))?.name,
                    ) }) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Configurar manualmente (RAW)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceLine(service: DiscoveredService) {
    val color = when {
        service.usable -> SuccessGreen
        !service.protocol.supported -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    Column {
        Text(
            text = "${service.protocol.label} · porta ${service.port}" +
                (service.resourcePath?.let { " · /$it" } ?: "") +
                " · " + service.sources.joinToString { it.label }.ifBlank { "verificação" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        service.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
