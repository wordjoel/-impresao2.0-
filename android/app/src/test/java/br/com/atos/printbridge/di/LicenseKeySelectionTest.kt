package br.com.atos.printbridge.di

import br.com.atos.printbridge.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D-EMU-1: separação de chaves entre variantes. Roda em testDebugUnitTest E testReleaseUnitTest.
 */
class LicenseKeySelectionTest {
    private val testKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/" +
        "aKBEpivJfg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ=="

    @Test
    fun `release usa a chave de producao mesmo se receber chave de teste`() {
        assertEquals(productionLicensePublicKeyForTest, selectLicensePublicKey(isDebugBuild = false, testPublicKeyB64 = testKey))
    }

    @Test
    fun `debug sem chave de teste usa producao, com chave de teste usa a de teste`() {
        assertEquals(productionLicensePublicKeyForTest, selectLicensePublicKey(isDebugBuild = true, testPublicKeyB64 = ""))
        assertEquals(testKey, selectLicensePublicKey(isDebugBuild = true, testPublicKeyB64 = testKey))
    }

    @Test
    fun `variante release compilada tem o campo da chave de teste vazio`() {
        if (!BuildConfig.DEBUG) {
            assertEquals("", BuildConfig.LICENSE_TEST_PUBLIC_KEY_SPKI_B64)
            assertEquals(productionLicensePublicKeyForTest, selectLicensePublicKey(BuildConfig.DEBUG, BuildConfig.LICENSE_TEST_PUBLIC_KEY_SPKI_B64))
        }
    }
}
