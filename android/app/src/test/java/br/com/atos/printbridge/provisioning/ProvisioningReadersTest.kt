package br.com.atos.printbridge.provisioning

import android.os.Build
import android.os.Bundle
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MDM (Bundle) e bancada (JSON) precisam gerar a MESMA configuração para as mesmas chaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ProvisioningReadersTest {

    private val json = """
        {
          "store_code": "4521", "store_name": "Loja Centro", "replace_printers": true,
          "printers": [
            {"name": "Expedição", "ip": "10.1.2.50", "port": 9100, "language": "ZPL", "model": "TSC_ML241",
             "dpi": 203, "paper": "100x72", "is_default": true},
            {"name": "Balança", "ip": "10.1.2.51", "language": "tspl", "dpi": 300, "paper": "60x40"}
          ],
          "max_attempts": 5, "retry_base_ms": 2000, "history_retention_days": 30, "admin_pin": "1234"
        }
    """.trimIndent()

    private fun managedBundle() = Bundle().apply {
        putString("store_code", "4521")
        putString("store_name", "Loja Centro")
        putBoolean("replace_printers", true)
        putParcelableArray(
            "printers",
            arrayOf(
                Bundle().apply {
                    putString("name", "Expedição"); putString("ip", "10.1.2.50"); putInt("port", 9100)
                    putString("language", "ZPL"); putString("model", "TSC_ML241"); putString("dpi", "203")
                    putString("paper", "100x72"); putBoolean("is_default", true)
                },
                // Console de MDM que só preencheu o essencial: o resto vem dos defaults do esquema.
                Bundle().apply {
                    putString("name", "Balança"); putString("ip", "10.1.2.51"); putString("language", "TSPL")
                    putString("dpi", "300"); putString("paper", "60x40")
                },
            ),
        )
        putInt("max_attempts", 5)
        putInt("retry_base_ms", 2000)
        putInt("history_retention_days", 30)
        putString("admin_pin", "1234")
    }

    @Test
    fun `json from the staging bench is fully parsed`() {
        val c = ProvisioningReaders.fromJson(json)

        assertEquals("4521", c.storeCode)
        assertTrue(c.replacePrinters)
        assertEquals(2, c.printers.size)
        assertEquals(PrinterLanguage.TSPL, c.printers[1].language)
        assertEquals(9100, c.printers[1].port)
        assertEquals(PrinterModel.TSC_ML241, c.printers[1].model)
        assertEquals(Triple(5, 2000L, 30), Triple(c.maxAttempts, c.retryBaseIntervalMillis, c.historyRetentionDays))
    }

    @Test
    fun `MDM bundle and bench json with the same keys produce the same configuration`() {
        val fromMdm = ProvisioningReaders.fromManagedConfig(managedBundle())
        val fromBench = ProvisioningReaders.fromJson(json)

        assertEquals(fromBench, fromMdm)
        assertEquals(fromBench.fingerprint(), fromMdm.fingerprint())
    }

    @Test
    fun `empty MDM configuration means nothing to apply`() {
        assertTrue(ProvisioningReaders.fromManagedConfig(Bundle()).isEmpty)
    }

    @Test
    fun `unknown language is kept as AUTO so validation rejects it instead of guessing`() {
        val c = ProvisioningReaders.fromJson("""{"printers":[{"name":"x","ip":"10.0.0.1","language":"EPL"}]}""")
        assertEquals(PrinterLanguage.AUTO, c.printers.single().language)
    }
}
