@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package br.com.atos.printbridge.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.ui.brand.LocalBrand
import br.com.atos.printbridge.ui.common.AppTopBar
import br.com.atos.printbridge.ui.common.BrandFooter
import br.com.atos.printbridge.ui.theme.Spacing

/**
 * Tela "Sobre": identidade da marca ativa + versão + fabricante/desenvolvedor + suporte.
 * Nunca expõe token, PIN, IP de impressora ou qualquer dado operacional.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val brand = LocalBrand.current

    Scaffold(topBar = { AppTopBar(title = "Sobre", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(Spacing.screenPadding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(Spacing.cardInnerPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(brand.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    InfoRow("Cliente / marca", brand.shortName)
                    InfoRow("Perfil de identidade", brand.brandId)
                    InfoRow("Desenvolvedor", "ATHOS Tecnologia")
                    brand.supportName?.let { InfoRow("Suporte", it) }
                    brand.supportUrl?.let { InfoRow("Suporte (URL)", it) }
                }
            }

            Text(
                "Núcleo Atos Print Bridge: enfileiramento persistente e impressão na rede local por IPP (escritório) e RAW (etiquetas ZPL/TSPL), " +
                    "offline-first. O status \"enviado\" não confirma impressão física.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BrandFooter()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
