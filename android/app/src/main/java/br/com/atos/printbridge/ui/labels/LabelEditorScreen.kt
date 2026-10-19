package br.com.atos.printbridge.ui.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import br.com.atos.printbridge.domain.model.label.BarcodeType
import br.com.atos.printbridge.domain.model.label.LabelAlign
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelField
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.LoadingState
import br.com.atos.printbridge.ui.theme.Spacing
import java.util.Locale

@Composable
fun LabelEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: LabelEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }
    LaunchedEffect(state.savedAndExit) {
        if (state.savedAndExit) onSaved()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (state.isNew) "Nova etiqueta" else "Editar etiqueta",
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::save) {
                        Icon(Icons.Filled.Check, contentDescription = "Salvar etiqueta")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing)) {
                StepperField(
                    label = "Largura (mm)",
                    value = state.widthMm.toFloat(),
                    onChange = { viewModel.onWidthChange(if (it > state.widthMm) 1 else -1) },
                    modifier = Modifier.weight(1f),
                )
                StepperField(
                    label = "Altura (mm)",
                    value = state.heightMm.toFloat(),
                    onChange = { viewModel.onHeightChange(if (it > state.heightMm) 1 else -1) },
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resolução", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelTemplate.AVAILABLE_DPIS.forEach { dpi ->
                        FilterChip(
                            selected = state.dpi == dpi,
                            onClick = { viewModel.onDpiChange(dpi) },
                            label = { Text("$dpi dpi") },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Layout — arraste os elementos para posicionar", style = MaterialTheme.typography.labelLarge)
                LabelCanvas(
                    template = state.toTemplate(),
                    data = LabelData.sample(),
                    modifier = Modifier.fillMaxWidth(),
                    selectedElementId = state.selectedElementId,
                    onSelectElement = viewModel::selectElement,
                    onMoveElement = { id, dx, dy -> viewModel.moveElement(id, dx, dy) },
                )
                if (state.elements.isEmpty()) {
                    Text(
                        "Nenhum elemento ainda. Adicione um abaixo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Adicionar elemento", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LabelElementType.entries.toList()) { type ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.addElement(type) },
                            label = { Text(type.label) },
                        )
                    }
                }
            }

            state.selectedElement?.let { element ->
                ElementProperties(
                    element = element,
                    viewModel = viewModel,
                )
            }

            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ElementProperties(element: LabelElement, viewModel: LabelEditorViewModel) {
    val id = element.id

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.cardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = element.type.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.deleteElement(id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            }

            if (element.type == LabelElementType.CAMPO ||
                element.type == LabelElementType.CODIGO_BARRAS ||
                element.type == LabelElementType.QRCODE
            ) {
                DropdownChoice(
                    label = "Campo variável",
                    options = LabelField.entries.map { it to it.label },
                    selected = element.field ?: LabelField.PRODUTO,
                    onSelect = { viewModel.setField(id, it) },
                )
            }

            if (element.type == LabelElementType.TEXTO) {
                OutlinedTextField(
                    value = element.text,
                    onValueChange = { viewModel.setText(id, it) },
                    label = { Text("Texto") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (element.type == LabelElementType.CODIGO_BARRAS) {
                DropdownChoice(
                    label = "Simbologia",
                    options = BarcodeType.entries.map { it to it.label },
                    selected = element.barcodeType,
                    onSelect = { viewModel.setBarcodeType(id, it) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing)) {
                StepperField("X (mm)", element.xMm, { viewModel.nudgeElement(id, it - element.xMm, 0f) }, Modifier.weight(1f))
                StepperField("Y (mm)", element.yMm, { viewModel.nudgeElement(id, 0f, it - element.yMm) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.listItemSpacing)) {
                StepperField("Largura (mm)", element.widthMm, { viewModel.resizeElement(id, it - element.widthMm, 0f) }, Modifier.weight(1f))
                StepperField("Altura (mm)", element.heightMm, { viewModel.resizeElement(id, 0f, it - element.heightMm) }, Modifier.weight(1f))
            }

            val usesText = element.type == LabelElementType.TEXTO ||
                element.type == LabelElementType.CAMPO ||
                element.type == LabelElementType.CODIGO_BARRAS
            if (usesText) {
                StepperField(
                    label = "Fonte (mm)",
                    value = element.fontSizeMm,
                    step = 0.5f,
                    min = LabelElement.MIN_FONT_MM,
                    max = LabelElement.MAX_FONT_MM,
                    onChange = { viewModel.setFontSize(id, it - element.fontSizeMm) },
                )
            }

            if (element.type == LabelElementType.LINHA || element.type == LabelElementType.CAIXA) {
                StepperField(
                    label = "Espessura (mm)",
                    value = element.thicknessMm,
                    step = 0.1f,
                    min = LabelElement.MIN_THICKNESS_MM,
                    max = LabelElement.MAX_THICKNESS_MM,
                    onChange = { viewModel.setThickness(id, it - element.thicknessMm) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Rotação", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelElement.ROTATIONS.forEach { rotation ->
                        FilterChip(
                            selected = element.rotationDeg == rotation,
                            onClick = { viewModel.setRotation(id, rotation) },
                            label = { Text("$rotation°") },
                        )
                    }
                }
            }

            if (usesText) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Alinhamento", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelAlign.entries.forEach { align ->
                            FilterChip(
                                selected = element.align == align,
                                onClick = { viewModel.setAlign(id, align) },
                                label = { Text(align.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> DropdownChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "—"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Controle de valor por passos (-/+), não por teclado: a edição acontece em ambiente de chão de
 * fábrica (possivelmente com luvas) e digitar decimais num teclado numérico de 720px é a parte mais
 * frágil do fluxo. O passo padrão é 0,5 mm.
 */
@Composable
private fun StepperField(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0.5f,
    min: Float = 0f,
    max: Float = 500f,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - step).coerceAtLeast(min)) },
                enabled = value > min,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Diminuir $label")
            }
            Text(
                text = formatNumber(value),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(44.dp),
            )
            IconButton(
                onClick = { onChange((value + step).coerceAtMost(max)) },
                enabled = value < max,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Aumentar $label")
            }
        }
    }
}

private fun formatNumber(value: Float): String = String.format(Locale.US, "%.1f", value)
