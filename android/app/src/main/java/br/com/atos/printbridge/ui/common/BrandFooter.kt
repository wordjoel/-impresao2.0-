package br.com.atos.printbridge.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.atos.printbridge.ui.brand.LocalBrand

/**
 * Rodapé discreto e configurável (`BrandConfig.footerEnabled`/`footerText`). Fica no fim do
 * conteúdo das telas principais, sem competir com a marca do cliente nem ocupar área de operação.
 */
// `compact`: sem o respiro superior, para telas que precisam caber inteiras sem rolagem (Home).
@Composable
fun BrandFooter(modifier: Modifier = Modifier, compact: Boolean = false) {
    val brand = LocalBrand.current
    if (!brand.footerEnabled) return

    Text(
        text = brand.footerText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) 0.dp else 16.dp, bottom = if (compact) 0.dp else 8.dp),
    )
}
