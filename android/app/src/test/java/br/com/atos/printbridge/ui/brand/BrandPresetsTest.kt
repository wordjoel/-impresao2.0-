package br.com.atos.printbridge.ui.brand

import br.com.atos.printbridge.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM puros (sem Compose/Robolectric): garantem que os perfis de marca são dados
 * consistentes. O cálculo de contraste é uma cópia consciente de `BrandTheme.onColorFor`
 * (que não pode ser importado aqui sem carregar classes do Compose) — por isso ele é
 * reimplementado com dedicação e a asserção é sobre a REGRA (melhor contraste entre
 * branco e preto >= AA), de modo que uma cor de cliente nova e ruim falhe o teste.
 */
class BrandPresetsTest {

    @Test
    fun `every declared brand id resolves to a preset with the matching key`() {
        BrandId.entries.forEach { id ->
            val preset = BrandPresets.of(id)
            assertEquals(id.key, preset.brandId)
        }
        assertEquals(BrandId.entries.size, BrandPresets.all.size)
    }

    @Test
    fun `presets use unique keys and cover exactly the declared ids`() {
        val keys = BrandPresets.all.map { it.brandId }
        assertEquals("chaves duplicadas", keys.size, keys.toSet().size)
        assertEquals(BrandId.entries.map { it.key }.toSet(), keys.toSet())
    }

    @Test
    fun `default brand is the flavor brand and is used whenever the stored key is missing or unknown`() {
        // Roda em cada flavor (testCarrefourDebugUnitTest, testAssaiDebugUnitTest...): prova que a
        // chave do flavor existe e vira a marca padrão daquele APK.
        assertEquals(BuildConfig.CLIENT_BRAND_KEY, DEFAULT_BRAND_ID.key)
        val flavorPreset = BrandPresets.of(DEFAULT_BRAND_ID)
        assertEquals(flavorPreset, BrandPresets.current(null))
        assertEquals(flavorPreset, BrandPresets.current(""))
        assertEquals(flavorPreset, BrandPresets.current("marca-inexistente"))
    }

    @Test
    fun `locked client apk ignores a brand stored by a previous install`() {
        assertEquals("assai", resolveBrandKey(stored = "carrefour", locked = true, default = BrandId.ASSAI))
        assertEquals("assai", resolveBrandKey(stored = null, locked = true, default = BrandId.ASSAI))
    }

    @Test
    fun `unlocked generic apk honours the stored brand and falls back to the default`() {
        assertEquals("rodao", resolveBrandKey(stored = "rodao", locked = false, default = BrandId.GENERIC))
        assertEquals("generic", resolveBrandKey(stored = null, locked = false, default = BrandId.GENERIC))
    }

    @Test
    fun `stored key round trips back to the same preset`() {
        BrandPresets.all.forEach { preset ->
            assertEquals(preset, BrandPresets.current(preset.brandId))
            assertEquals(BrandId.fromKey(preset.brandId).key, preset.brandId)
        }
    }

    @Test
    fun `preset names are usable in the app bar and contain the short name`() {
        BrandPresets.all.forEach { preset ->
            assertTrue("displayName vazio em ${preset.brandId}", preset.displayName.isNotBlank())
            assertTrue("shortName vazio em ${preset.brandId}", preset.shortName.isNotBlank())
            assertTrue(
                "displayName '${preset.displayName}' não contém shortName '${preset.shortName}'",
                preset.displayName.contains(preset.shortName),
            )
            assertTrue("displayName longo demais para a top bar: ${preset.displayName}", preset.displayName.length <= 40)
        }
    }

    @Test
    fun `footer is coherent - when enabled there is a text to show`() {
        BrandPresets.all.forEach { preset ->
            if (preset.footerEnabled) {
                assertTrue("rodapé habilitado e vazio em ${preset.brandId}", preset.footerText.isNotBlank())
            }
        }
    }

    @Test
    fun `every brand color is opaque and non zero`() {
        BrandPresets.all.forEach { preset ->
            listOf(preset.primaryColor, preset.secondaryColor, preset.accentColor, preset.surfaceColor).forEach { color ->
                assertEquals("cor translúcida em ${preset.brandId}: $color", 0xFF, ((color shr 24) and 0xFF).toInt())
                assertNotEquals("cor zerada em ${preset.brandId}", 0L, color and 0xFFFFFF)
            }
        }
    }

    @Test
    fun `every brand color keeps wcag aa for the text color the theme places over it`() {
        BrandPresets.all.forEach { preset ->
            listOf(preset.primaryColor, preset.secondaryColor, preset.accentColor).forEach { color ->
                val contrast = bestOnColorContrast(color)
                assertTrue(
                    "contraste ${"%.2f".format(contrast)}:1 abaixo de AA (4.5) em ${preset.brandId} para ${color.toString(16)}",
                    contrast >= 4.5,
                )
            }
        }
    }

    @Test
    fun `brands are visually distinct from each other`() {
        assertEquals(
            "dois perfis com a mesma cor primária",
            BrandPresets.all.size,
            BrandPresets.all.map { it.primaryColor }.toSet().size,
        )
        BrandPresets.all.forEach { preset ->
            assertNotEquals("primária igual à secundária em ${preset.brandId}", preset.primaryColor, preset.secondaryColor)
            assertNotEquals("primária igual ao acento em ${preset.brandId}", preset.primaryColor, preset.accentColor)
        }
    }

    // --- espelho de BrandTheme.onColorFor (ver KDoc da classe) -----------------------------------

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    private fun bestOnColorContrast(argb: Long): Double {
        val l = luminance(argb)
        return maxOf(1.05 / (l + 0.05), (l + 0.05) / 0.05)
    }
}
