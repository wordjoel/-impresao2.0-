package br.com.atos.printbridge.ui.brand

/**
 * Identidade visual de um cliente. O core do aplicativo nunca conhece uma marca concreta: telas,
 * tema, rodapé e tela "Sobre" leem sempre deste contrato, resolvido em runtime por
 * [BrandPresets] a partir da marca selecionada em Configurações → Administração.
 *
 * Cores em ARGB (`0xAARRGGBB`) para manter o modelo independente de Compose e testável em JVM pura.
 * Asset de marca (logo/splash/ícone do launcher) é sempre um placeholder até o cliente fornecer o
 * material oficial/licenciado — ver `WHITE-LABEL-ARCHITECTURE.md`.
 */
data class BrandConfig(
    val brandId: String,
    val displayName: String,
    val shortName: String,
    /** Nome discreto do produto exibido abaixo da logomarca na tela de entrada (ex.: "Print Bridge"). */
    val subBrandName: String = "Print Bridge",
    /** Fabricante/integrador exibido discreto no rodapé da tela de entrada (ex.: "ATHOS"). */
    val vendorName: String = "ATHOS",
    val primaryColor: Long,
    val secondaryColor: Long,
    val accentColor: Long,
    val surfaceColor: Long,
    val logoResource: Int,
    val splashLogoResource: Int,
    val launcherIconProfile: String,
    val footerEnabled: Boolean,
    val footerText: String,
    val supportName: String? = null,
    val supportUrl: String? = null,
)
