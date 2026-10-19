@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.queue

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun QueueScreen(onBack: () -> Unit, viewModel: QueueViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    // A lista se reorganiza sozinha (job enviado/falhou sai dela): sem confirmação, o toque em
    // "Remover" pode cair no job de baixo. A confirmação diz exatamente qual job sai.
    var toRemove by remember { mutableStateOf<PrintJob?>(null) }

    toRemove?.let { job ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remover da fila?") },
            text = { Text("\"${job.documentName}\" não será impresso. Ele continua no histórico como Removido.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(job.id)
                    toRemove = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Voltar") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
        )
    }

    Scaffold(topBar = { AppTopBar(title = "Fila de impressão", onBack = onBack) }) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            state.jobs.isEmpty() -> EmptyState(
                title = "Fila vazia",
                description = "Nenhum trabalho aguardando ou em processamento no momento.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).padding(Spacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing),
            ) {
                items(state.jobs, key = { it.id }) { job ->
                    QueueJobCard(
                        job = job,
                        printerName = state.printerNames[job.printerId] ?: job.printerId,
                        onRetry = { viewModel.retry(job.id) },
                        onDelete = { toRemove = job },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueJobCard(
    job: PrintJob,
    printerName: String,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
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
            Text(printerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${timeFormat.format(Date(job.createdAt))} · tentativa ${job.attempts}/${job.maxAttempts}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                job.errorMessage?.let {
                    Text("Último erro: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                job.validationWarnings.forEach {
                    Text("Aviso ZPL: $it", style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
                }
                Text(
                    "\"Enviado\" indica que os dados foram entregues à conexão de impressão, " +
                        "não uma confirmação física de que a etiqueta saiu impressa.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    if (job.status == PrintJobStatus.FAILED || job.status == PrintJobStatus.PENDING) {
                        TextButton(onClick = onRetry) { Text("Tentar novamente") }
                    }
                    if (job.status != PrintJobStatus.PROCESSING) {
                        TextButton(onClick = onDelete) { Text("Remover") }
                    }
                }
            }
        }
    }
}
