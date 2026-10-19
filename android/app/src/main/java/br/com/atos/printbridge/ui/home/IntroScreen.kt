package br.com.atos.printbridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import br.com.atos.printbridge.R
import br.com.atos.printbridge.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Tela de entrada (start destination): APENAS o nome do APK (`app_name`), centralizado, sobre o
 * mesmo fundo da splash do sistema. Nenhuma logo, nome ou cor de cliente aqui — a logo do cliente
 * aparece só na tela de módulos (Home). Auto-avança após ~2.5s e também avança ao toque.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    var finished by remember { mutableStateOf(false) }

    fun finish() {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        delay(2500)
        finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background))
            .clickable { finish() }
            .padding(Spacing.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            // Neutro de propósito: a cor da marca do cliente só entra a partir da Home.
            color = Color(0xFF1F2937),
            textAlign = TextAlign.Center,
        )
    }
}
