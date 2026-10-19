@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.atos.printbridge.ui.brand.LocalBrand
import br.com.atos.printbridge.ui.brand.onColorFor
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.BrandFooter
import br.com.atos.printbridge.ui.theme.ErrorRed
import br.com.atos.printbridge.ui.theme.IconSizes
import br.com.atos.printbridge.ui.theme.Spacing
import br.com.atos.printbridge.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

private data class HomeAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/** Cores de módulo consistentes com a identidade Carrefour (chaves = rótulos exatos dos botões). */
private val ModuleColors = mapOf(
    "Etiquetas" to 0xFF004E9A,
    "Fila" to 0xFFC81F0E,
    "Impressoras" to 0xFF0073B9,
    "Histórico" to 0xFF1B2A4A,
    "Configurações" to 0xFF42526B,
)

/** Altura mínima de toque dos tiles de módulo (operação com luvas). */
private val ModuleTileHeight = 56.dp

/** Espaço entre blocos da Home: menor que o padrão para tudo caber numa tela de 360x720dp. */
private val HomeGap = 10.dp

/** Abaixo desta altura útil (ex.: coletor em paisagem) a Home volta a rolar em vez de espremer os botões. */
private val MinHeightWithoutScroll = 480.dp

/**
 * Tela principal do operador: estado da impressão (impressora padrão, rede, fila), impressão de
 * etiqueta de teste e acesso rápido aos módulos. Identidade visual da marca ativa no topo,
 * acesso direto — sem tela de login.
 */
@Composable
fun HomeScreen(
    onNavigateToQueue: () -> Unit,
    onNavigateToPrinters: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLabels: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val brand = LocalBrand.current

    Scaffold(
        // Tela de primeiro nível: sem ação de voltar. Logo da marca em destaque + nome do produto
        // discreto (ex.: "Print Bridge") em letra pequena.
        topBar = { AppTopBar(title = brand.displayName, logoResource = brand.logoResource, subtitle = brand.subBrandName) },
    ) { padding ->
        // Tudo cabe numa tela, sem rolagem: os tiles de módulo ocupam a altura que sobra (weight)
        // em vez de serem quadrados fixos, então a tela se adapta a qualquer coletor (CF550,
        // MC9190, telas maiores). Só numa tela baixa demais (ex.: paisagem) a rolagem volta, para
        // nunca cortar um botão.
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            val fitsWithoutScroll = maxHeight >= MinHeightWithoutScroll
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (fitsWithoutScroll) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = Spacing.screenPadding, vertical = HomeGap),
                verticalArrangement = Arrangement.spacedBy(HomeGap),
            ) {
                StatusCard(state)
                Button(
                    onClick = viewModel::printSampleLabel,
                    modifier = Modifier.fillMaxWidth().height(ModuleTileHeight),
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(IconSizes.inline))
                    Spacer(Modifier.size(8.dp))
                    Text("Imprimir teste na impressora padrão")
                }
                feedback?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ModuleButtons(
                    actions = listOf(
                        HomeAction("Impressoras", Icons.Filled.Print, onNavigateToPrinters),
                        HomeAction("Fila", Icons.Filled.Queue, onNavigateToQueue),
                        HomeAction("Histórico", Icons.Filled.History, onNavigateToHistory),
                        HomeAction("Etiquetas", Icons.Filled.Label, onNavigateToLabels),
                        HomeAction("Configurações", Icons.Filled.Settings, onNavigateToSettings),
                    ),
                    fillHeight = fitsWithoutScroll,
                    modifier = if (fitsWithoutScroll) Modifier.weight(1f) else Modifier,
                )
                BrandFooter(compact = true)
            }
        }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(4000)
            viewModel.clearFeedback()
        }
    }
}

/**
 * Grade 2x2 de módulos + Configurações em largura total. Com [fillHeight], as duas linhas dividem
 * a altura disponível; sem ele (tela baixa, com rolagem), os tiles voltam a ser quadrados.
 */
@Composable
private fun ModuleButtons(actions: List<HomeAction>, fillHeight: Boolean, modifier: Modifier = Modifier) {
    val body = actions.dropLast(1)
    val last = actions.lastOrNull()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HomeGap),
    ) {
        body.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(HomeGap),
            ) {
                pair.forEach { action ->
                    ModuleTile(
                        action = action,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.aspectRatio(1f)),
                    )
                }
            }
        }
        last?.let { ModuleWideButton(action = it) }
    }
}

@Composable
private fun ModuleTile(action: HomeAction, modifier: Modifier = Modifier) {
    val moduleColor = ModuleColors[action.label] ?: 0xFF1B2A4A
    val contentColor = onColorFor(moduleColor)
    // Tile com bordas arredondadas, ícone + rótulo centralizados, área grande para operação com luvas.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = ModuleTileHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(moduleColor))
            .clickable { action.onClick() },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(action.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(IconSizes.moduleTile))
            Spacer(Modifier.height(6.dp))
            Text(
                action.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ModuleWideButton(action: HomeAction) {
    val moduleColor = ModuleColors[action.label] ?: 0xFF1B2A4A
    val contentColor = onColorFor(moduleColor)
    Button(
        onClick = action.onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(moduleColor), contentColor = contentColor),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().height(ModuleTileHeight),
    ) {
        Icon(action.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(IconSizes.inline))
        Spacer(Modifier.width(10.dp))
        Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Estado da impressão num bloco compacto: impressora padrão e rede à esquerda, contadores da fila
 * à direita. Mesma informação de antes em cerca de metade da altura.
 */
@Composable
private fun StatusCard(state: HomeUiState) {
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Impressora padrão", style = MaterialTheme.typography.labelMedium, color = onCard)
                Text(
                    text = state.defaultPrinter?.let { it.name.ifBlank { "Impressora ${it.ipAddress}" } }
                        ?: "Nenhuma configurada",
                    style = MaterialTheme.typography.titleMedium,
                    color = onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.defaultPrinter?.let {
                    Text(
                        "${it.ipAddress}:${it.port} · ${it.protocol.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = onCard,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.defaultPrinterStatus?.let { s ->
                    Text(
                        if (s.online) s.status else "Impressora padrão indisponível. ${s.status}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = if (s.online) SuccessGreen else ErrorRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (state.networkAvailable) Icons.Filled.CheckCircle else Icons.Filled.WifiOff,
                        contentDescription = if (state.networkAvailable) "Rede disponível" else "Sem rede",
                        tint = if (state.networkAvailable) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(IconSizes.inline),
                    )
                    Text(
                        if (state.networkAvailable) "Rede disponível" else "Sem rede",
                        style = MaterialTheme.typography.bodySmall,
                        color = onCard,
                    )
                }
            }
            MetricLabel("Aguardando", state.pendingCount)
            MetricLabel("Falhas 24 h", state.failedCount, isError = true)
        }
    }
}

@Composable
private fun MetricLabel(label: String, value: Int, isError: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (isError && value > 0) ErrorRed else MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
