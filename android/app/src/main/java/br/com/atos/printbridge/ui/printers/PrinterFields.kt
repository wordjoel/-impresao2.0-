package br.com.atos.printbridge.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import br.com.atos.printbridge.domain.model.Printer

/** Uma linha "Rótulo: valor". Valor ausente aparece como "Não informado", nunca como um palpite. */
@Composable
fun PrinterField(label: String, value: String?, monospace: Boolean = false, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: Printer.NOT_INFORMED,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = when {
                value.isNullOrBlank() -> MaterialTheme.colorScheme.outline
                valueColor != null -> valueColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Campos exigidos na tela de Impressoras, na ordem pedida. */
@Composable
fun PrinterIdentityFields(
    manufacturer: String?,
    model: String?,
    address: String,
    hostname: String?,
    port: Int?,
    protocol: String?,
    status: String?,
    statusColor: Color? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PrinterField("Fabricante", manufacturer)
        PrinterField("Modelo", model)
        PrinterField("IP", address, monospace = true)
        if (!hostname.isNullOrBlank()) PrinterField("Hostname", hostname, monospace = true)
        PrinterField("Porta", port?.toString(), monospace = true)
        PrinterField("Protocolo", protocol)
        PrinterField("Status", status, valueColor = statusColor)
    }
}
