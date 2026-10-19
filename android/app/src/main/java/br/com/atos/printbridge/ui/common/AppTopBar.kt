package br.com.atos.printbridge.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.atos.printbridge.ui.brand.LocalBrand

// Barra superior única do app: garante título, ação de voltar e ações no mesmo lugar,
// no mesmo estilo e com o mesmo rótulo de acessibilidade em todas as telas.
// `onBack == null` em telas de primeiro nível (ex.: Home), que não têm retorno.
// Fundo BRANCO com conteúdo na cor primária da marca: as logomarcas de cliente (ex.: Carrefour,
// azul + vermelho sobre branco) somem sobre uma barra da própria cor primária — o azul do logo
// quase desaparecia no azul da barra. A identidade fica na faixa inferior na cor da marca.
// A cor de conteúdo vem sempre da marca ativa, nunca fixa por cliente.
// Com logo ativo, a logomarca do cliente é o elemento em destaque e o nome do produto
// (`subtitle`, ex.: "Print Bridge") aparece discreto, em letra pequena abaixo dela.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    logoResource: Int? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val brandColor = Color(LocalBrand.current.primaryColor)
    Column {
        TopAppBar(
            title = {
                if (logoResource != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Image(
                            painter = painterResource(logoResource),
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(40.dp)
                                .aspectRatio(2.5f),
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = brandColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                } else {
                    Text(title)
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White,
                titleContentColor = brandColor,
                navigationIconContentColor = brandColor,
                actionIconContentColor = brandColor,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(brandColor),
        )
    }
}
