@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.EmptyState
import br.com.atos.printbridge.ui.common.LoadingState
import br.com.atos.printbridge.ui.common.StatusBadge
import br.com.atos.printbridge.ui.theme.Spacing
import br.com.atos.printbridge.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(topBar = { AppTopBar(title = "Histórico", onBack = onBack) }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PeriodFilter.entries.forEach { period ->
                    FilterChip(
                        selected = state.filter.period == period,
                        onClick = { viewModel.setPeriodFilter(period) },
                        label = { Text(period.label) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = state.filter.status == null, onClick = { viewModel.setStatusFilter(null) }, label = { Text("Todos") })
                listOf(PrintJobStatus.SENT, PrintJobStatus.FAILED, PrintJobStatus.CANCELLED, PrintJobStatus.DELETED_BY_OPERATOR).forEach { status ->
                    FilterChip(
                        selected = state.filter.status == status,
                        onClick = { viewModel.setStatusFilter(status) },
                        label = { Text(status.name) },
                    )
                }
            }

            val filtered = state.filteredJobs
            when {
                state.isLoading -> LoadingState()
                filtered.isEmpty() -> EmptyState(
                    title = "Nenhum registro",
                    description = "Nenhum trabalho encontrado para os filtros selecionados.",
                )
                else -> LazyColumn(
                    modifier = Modifier.padding(Spacing.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing),
                ) {
                    items(filtered, key = { it.id }) { job ->
                        HistoryJobCard(
                            job = job,
                            // Retrato gravado no envio (v8) primeiro; jobs antigos caem no cadastro atual.
                            printerName = job.printerName ?: state.printerNames[job.printerId] ?: job.printerId,
                            onReprint = { viewModel.reprint(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryJobCard(job: PrintJob, printerName: String, onReprint: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.documentName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(job.status)
            }
            Text(
                printerName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.printerAddress != null || job.printerProtocol != null) {
                Text(
                    listOfNotNull(job.printerAddress, job.printerProtocol, job.type.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Criado ${dateFormat.format(Date(job.createdAt))}" +
                    (job.sentAt?.let { " · enviado ${dateFormat.format(Date(it))}" } ?: " · atualizado ${dateFormat.format(Date(job.updatedAt))}") +
                    " · ${job.attempts} tentativa(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            job.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            job.validationWarnings.forEach {
                Text("Aviso ZPL: $it", style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
            }
            // Reimprimir é uma intenção nova (job novo): vale também para removido/cancelado.
            if (!job.status.isActive) {
                TextButton(onClick = onReprint) { Text("Reimprimir") }
            }
        }
    }
}

