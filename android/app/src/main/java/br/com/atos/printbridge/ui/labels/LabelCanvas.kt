package br.com.atos.printbridge.ui.labels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.atos.printbridge.domain.model.label.BarcodeType
import br.com.atos.printbridge.domain.model.label.LabelAlign
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.model.label.barcode.BarcodeEncoder

/**
 * Desenha a etiqueta em escala, com todos os elementos posicionados em **milímetros**. É o mesmo
 * componente no editor (interativo) e na pré-visualização (somente leitura), garantindo que o que o
 * operador vê é o mesmo layout — a diferença é só [onSelectElement]/[onMoveElement] nulos.
 */
@Composable
fun LabelCanvas(
    template: LabelTemplate,
    data: LabelData,
    modifier: Modifier = Modifier,
    selectedElementId: String? = null,
    onSelectElement: ((String) -> Unit)? = null,
    onMoveElement: ((String, Float, Float) -> Unit)? = null,
) {
    val interactive = onMoveElement != null || onSelectElement != null
    BoxWithConstraints(modifier) {
        val scale: Dp = if (template.widthMm > 0) maxWidth / template.widthMm else 1.dp
        val sheetHeight: Dp = scale * template.heightMm
        val pxPerMm = with(LocalDensity.current) { scale.toPx() }

        Box(
            modifier = Modifier
                .size(maxWidth, sheetHeight)
                .background(Color.White)
                .border(1.dp, Color(0xFF9E9E9E)),
        ) {
            template.elements.forEach { element ->
                LabelElementView(
                    element = element,
                    template = template,
                    data = data,
                    scale = scale,
                    pxPerMm = pxPerMm,
                    selected = interactive && element.id == selectedElementId,
                    interactive = interactive,
                    onSelectElement = onSelectElement,
                    onMoveElement = onMoveElement,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.LabelElementView(
    element: LabelElement,
    template: LabelTemplate,
    data: LabelData,
    scale: Dp,
    pxPerMm: Float,
    selected: Boolean,
    interactive: Boolean,
    onSelectElement: ((String) -> Unit)?,
    onMoveElement: ((String, Float, Float) -> Unit)?,
) {
    val widthDp = (scale * element.widthMm).coerceAtLeast(1.dp)
    val heightDp = (scale * element.heightMm).coerceAtLeast(1.dp)

    var m = Modifier
        .offset(x = scale * element.xMm, y = scale * element.yMm)
        .size(widthDp, heightDp)

    if (interactive) {
        m = m.clickable { onSelectElement?.invoke(element.id) }
        if (onMoveElement != null) {
            m = m.pointerInput(element.id, pxPerMm) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (pxPerMm > 0f) {
                        onMoveElement(element.id, dragAmount.x / pxPerMm, dragAmount.y / pxPerMm)
                    }
                }
            }
        }
    }
    if (selected) {
        m = m.border(2.dp, MaterialTheme.colorScheme.primary)
    }

    Box(modifier = m, contentAlignment = Alignment.Center) {
        when (element.type) {
            LabelElementType.TEXTO, LabelElementType.CAMPO -> ElementText(element, data, scale)
            LabelElementType.CODIGO_BARRAS -> BarcodeView(element.resolveValue(data), element.barcodeType)
            LabelElementType.QRCODE -> QrPlaceholderView(element.resolveValue(data))
            LabelElementType.IMAGEM -> ImagePlaceholder(scale, element.thicknessMm)
            LabelElementType.LINHA -> Box(Modifier.fillMaxSize().background(Color.Black))
            LabelElementType.CAIXA -> Box(
                Modifier
                    .fillMaxSize()
                    .border((element.thicknessMm * scale.value).coerceAtLeast(0.5f).dp, Color.Black),
            )
        }
    }
}

@Composable
private fun ElementText(element: LabelElement, data: LabelData, scale: Dp) {
    val value = element.resolveValue(data)
    if (value.isBlank()) return
    Text(
        text = value,
        color = Color.Black,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = when (element.align) {
            LabelAlign.LEFT -> TextAlign.Start
            LabelAlign.CENTER -> TextAlign.Center
            LabelAlign.RIGHT -> TextAlign.End
        },
        fontSize = (element.fontSizeMm * scale.value * 0.72f).coerceAtLeast(4f).sp,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BarcodeView(value: String, type: BarcodeType) {
    val modules = remember(value, type) { BarcodeEncoder.encode(type, value) }
    val barColor = Color.Black
    if (modules.isEmpty()) {
        Text(
            text = "código inválido",
            color = Color(0xFFB3261E),
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val moduleWidth = size.width / modules.size
        modules.forEachIndexed { index, isBar ->
            if (isBar) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(index * moduleWidth, 0f),
                    size = Size(moduleWidth + 0.6f, size.height),
                )
            }
        }
    }
}

/** Placeholder visual de QR: padrão determinístico + os 3 marcadores de localização. Não é
 * escaneável — o símbolo real é gerado pela impressora via `^BQ` (ver ZplLabelRenderer). */
@Composable
private fun QrPlaceholderView(value: String) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val grid = 21
        val cell = size.minDimension / grid
        val originX = (size.width - cell * grid) / 2f
        val originY = (size.height - cell * grid) / 2f
        repeat(grid) { row ->
            repeat(grid) { col ->
                val inFinder = (row < 7 && col < 7) || (row < 7 && col >= grid - 7) || (row >= grid - 7 && col < 7)
                val filled = if (inFinder) {
                    val r = if (row < 7) row else row - (grid - 7)
                    val c = if (col < 7) col else col - (grid - 7)
                    r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)
                } else {
                    ((value.hashCode() * 31 + row * 131 + col) and 7) > 3
                }
                if (filled) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(originX + col * cell, originY + row * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(scale: Dp, thicknessMm: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border((thicknessMm * scale.value).coerceAtLeast(0.5f).dp, Color(0xFF9E9E9E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color(0xFF9E9E9E),
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "[Imagem]",
                color = Color(0xFF9E9E9E),
                fontSize = 7.sp,
                fontFamily = FontFamily.SansSerif,
            )
        }
    }
}
