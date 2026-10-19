package br.com.atos.printbridge.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.ui.theme.ErrorRed
import br.com.atos.printbridge.ui.theme.SuccessGreen
import br.com.atos.printbridge.ui.theme.WarningAmber

@Composable
fun StatusBadge(status: PrintJobStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        PrintJobStatus.PENDING -> "Pendente" to MaterialTheme.colorScheme.secondary
        PrintJobStatus.PROCESSING -> "Processando" to WarningAmber
        PrintJobStatus.SENT -> "Enviado" to SuccessGreen
        PrintJobStatus.FAILED -> "Falhou" to ErrorRed
        PrintJobStatus.CANCELLED -> "Cancelado" to Color.Gray
        PrintJobStatus.DELETED_BY_OPERATOR -> "Removido" to Color.Gray
    }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
