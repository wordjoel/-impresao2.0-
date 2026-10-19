package br.com.atos.printbridge.ui.brand

import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.R

/**
 * Perfis de marca disponíveis. A chave é persistida (DataStore) e nunca o índice do enum, para que
 * reordenar/adicionar perfis não troque a marca de um dispositivo já instalado.
 */
enum class BrandId(val key: String) {
    GENERIC("generic"),
    CARREFOUR("carrefour"),
    RODAO("rodao"),
    ASSAI("assai"),
    ;

    companion object {
        fun fromKey(key: String?): BrandId = entries.firstOrNull { it.key == key } ?: DEFAULT_BRAND_ID
    }
}

/**
 * Perfil usado no primeiro start e como fallback de qualquer chave desconhecida: a marca do flavor
 * (cliente) deste APK. Chave de flavor inválida é erro de build, coberto por BrandPresetsTest.
 */
val DEFAULT_BRAND_ID: BrandId = BrandId.entries.firstOrNull { it.key == BuildConfig.CLIENT_BRAND_KEY }
    ?: error("CLIENT_BRAND_KEY '${BuildConfig.CLIENT_BRAND_KEY}' do flavor não corresponde a nenhum BrandId")

/**
 * Marca efetiva a partir da chave guardada no DataStore. No APK de um cliente (`locked`) a marca é
 * sempre a do flavor, mesmo que o aparelho tenha outra chave guardada de uma instalação anterior.
 */
fun resolveBrandKey(stored: String?, locked: Boolean, default: BrandId = DEFAULT_BRAND_ID): String =
    if (locked) default.key else stored ?: default.key

/**
 * Presets. Carrefour, Roldão e Assaí usam a arte enviada pelo cliente; o genérico usa a marca Atos.
 */
object BrandPresets {

    val GENERIC = BrandConfig(
        brandId = BrandId.GENERIC.key,
        displayName = "Atos Print Bridge",
        shortName = "Atos",
        primaryColor = 0xFF1B2A4A,
        secondaryColor = 0xFF2FB6A8,
        accentColor = 0xFF4FD1C5,
        surfaceColor = 0xFFF7F8FA,
        logoResource = R.drawable.ic_brand_mark,
        splashLogoResource = R.drawable.ic_brand_mark,
        launcherIconProfile = "atos_print_bridge",
        footerEnabled = true,
        footerText = "Powered by ATHOS Tecnologia",
        supportName = "ATHOS Tecnologia",
    )

    val CARREFOUR = BrandConfig(
        brandId = BrandId.CARREFOUR.key,
        displayName = "Carrefour Print Bridge",
        shortName = "Carrefour",
        primaryColor = 0xFF004E9A,
        secondaryColor = 0xFFC81F0E,
        accentColor = 0xFF0073B9,
        surfaceColor = 0xFFF5F7FA,
        logoResource = R.drawable.carrefour_brand_logo,
        splashLogoResource = R.drawable.carrefour_splash_logo,
        launcherIconProfile = "carrefour",
        footerEnabled = true,
        footerText = "Powered by ATHOS",
        supportName = "ATHOS Tecnologia",
    )

    // Roldão Atacadista: logo e cores medidas na arte enviada pelo cliente (2026-09-23) — pílula
    // azul #2E3192, fundo #2F2C6F, letras #FFF616. A chave "rodao" fica: é a persistida nos aparelhos.
    val RODAO = BrandConfig(
        brandId = BrandId.RODAO.key,
        displayName = "Roldão Print Bridge",
        shortName = "Roldão",
        primaryColor = 0xFF2E3192,
        secondaryColor = 0xFF2F2C6F,
        accentColor = 0xFFFFF616,
        surfaceColor = 0xFFF5F6FB,
        logoResource = R.drawable.roldao_brand_logo,
        splashLogoResource = R.drawable.roldao_splash_logo,
        launcherIconProfile = "rodao",
        footerEnabled = true,
        footerText = "Powered by ATHOS Tecnologia",
        supportName = "ATHOS Tecnologia",
    )

    val ASSAI = BrandConfig(
        brandId = BrandId.ASSAI.key,
        displayName = "Assaí Print Bridge",
        shortName = "Assaí",
        // Assaí Atacadista: logo e cores medidas na arte enviada pelo cliente (2026-09-23) — azul
        // #0039A4, vermelho da barra #D51B20, laranja do sol #F67A20. A logo mantém o fundo azul
        // (selo arredondado) porque "ATACADISTA" é branco e sumiria na barra branca.
        primaryColor = 0xFF0039A4,
        secondaryColor = 0xFFD51B20,
        accentColor = 0xFFF67A20,
        surfaceColor = 0xFFF5F7FC,
        logoResource = R.drawable.assai_brand_logo,
        splashLogoResource = R.drawable.assai_splash_logo,
        launcherIconProfile = "assai",
        footerEnabled = true,
        footerText = "Powered by ATHOS Tecnologia",
        supportName = "ATHOS Tecnologia",
    )

    private val byId: Map<BrandId, BrandConfig> = mapOf(
        BrandId.GENERIC to GENERIC,
        BrandId.CARREFOUR to CARREFOUR,
        BrandId.RODAO to RODAO,
        BrandId.ASSAI to ASSAI,
    )

    /** Todos os perfis, na ordem de exibição em Configurações → Administração. */
    val all: List<BrandConfig> = listOf(GENERIC, CARREFOUR, RODAO, ASSAI)

    fun of(id: BrandId): BrandConfig = byId.getValue(id)

    /** Resolve a marca a partir da chave persistida; chave ausente/desconhecida cai no default. */
    fun current(key: String?): BrandConfig = of(BrandId.fromKey(key))
}
