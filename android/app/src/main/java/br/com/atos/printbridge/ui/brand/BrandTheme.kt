package br.com.atos.printbridge.ui.brand

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import br.com.atos.printbridge.ui.theme.AtosTypography
import br.com.atos.printbridge.ui.theme.ErrorRed
import br.com.atos.printbridge.ui.theme.SurfaceDark

/** Marca ativa para toda a composição. Só [BrandTheme] deve publicá-la. */
val LocalBrand = staticCompositionLocalOf { BrandPresets.GENERIC }

// Ponto de virada WCAG entre texto claro e escuro: abaixo dele o branco contrasta mais; acima,
// o preto. Escolher sempre o melhor dos dois garante contraste >= 4.58:1 para QUALQUER cor de
// marca (o mínimo ocorre no próprio ponto de virada), então um cliente novo nunca gera texto
// ilegível — e a Fase 9 não precisa reprovar cor de cliente nenhum.
// Raciocínio: branco = 1.05/(L+0.05); preto = (L+0.05)/0.05; iguais em L = sqrt(0.0525)-0.05.
private const val ON_COLOR_LUMINANCE_CROSSOVER = 0.1791f

/** Cor de conteúdo acessível para um fundo da marca, calculada (nunca fixa por cliente). */
fun onColorFor(background: Long): Color =
    if (Color(background).luminance() > ON_COLOR_LUMINANCE_CROSSOVER) Color.Black else Color.White

/** Constrói o ColorScheme a partir do perfil de marca, preservando contraste AA. */
fun brandColorScheme(brand: BrandConfig, darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        darkColorScheme(
            primary = Color(brand.accentColor),
            onPrimary = onColorFor(brand.accentColor),
            primaryContainer = Color(brand.primaryColor),
            onPrimaryContainer = onColorFor(brand.primaryColor),
            secondary = Color(brand.secondaryColor),
            onSecondary = onColorFor(brand.secondaryColor),
            background = SurfaceDark,
            surface = Color(0xFF181D26),
            error = Color(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = Color(brand.primaryColor),
            onPrimary = onColorFor(brand.primaryColor),
            primaryContainer = Color(brand.primaryColor),
            onPrimaryContainer = onColorFor(brand.primaryColor),
            secondary = Color(brand.accentColor),
            onSecondary = onColorFor(brand.accentColor),
            secondaryContainer = Color(brand.secondaryColor),
            onSecondaryContainer = onColorFor(brand.secondaryColor),
            tertiary = Color(brand.secondaryColor),
            onTertiary = onColorFor(brand.secondaryColor),
            background = Color(brand.surfaceColor),
            surface = Color.White,
            error = ErrorRed,
        )
    }

@Composable
fun BrandTheme(
    brand: BrandConfig,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(brand, darkTheme) { brandColorScheme(brand, darkTheme) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            // Barra de status branca como a AppTopBar (fundo branco para a logomarca aparecer), ícones escuros.
            window.statusBarColor = Color.White.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    CompositionLocalProvider(LocalBrand provides brand) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AtosTypography,
            content = content,
        )
    }
}
