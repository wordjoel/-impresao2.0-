package br.com.atos.printbridge.license

import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.StoreLicenseTokenUseCase
import br.com.atos.printbridge.domain.license.EcdsaLicenseTokenVerifier
import br.com.atos.printbridge.application.usecase.EffectiveLicenseStatus
import br.com.atos.printbridge.testutil.FakeDeviceIdentityProvider
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64

/**
 * INTEGRAÇÃO com o MDM (D2): token EMITIDO de verdade pela rota POST /managed-apps/license do
 * gateway local (stack local do MDM-ON-02, chave de assinatura de TESTE) para o identificador
 * informado, verificado aqui pelo verificador real do app + regra D2 + avaliação de sempre.
 * A fixture (token, chave PÚBLICA de teste, identificador) vem de -Ppb.it.fixture; sem ela o
 * teste é ignorado e não conta como aprovado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ManagedLicenseGatewayTokenIntegrationTest {
    @Test
    fun tokenDaRotaD2DoGatewayLiberaAImpressao() = runTest {
        val path = System.getProperty("pb.it.fixture")?.takeIf { it.isNotBlank() }
        assumeTrue("pb.it.fixture ausente: integração não executada", path != null && File(path).exists())
        val f = JSONObject(File(path!!).readText())
        val verifier = EcdsaLicenseTokenVerifier(Base64.getDecoder().decode(f.getString("public_key_spki_b64")))
        val identity = FakeDeviceIdentityProvider(f.getString("license_device_id"))
        val repo = FakeLicenseRepository()
        val link = ManagedLicenseLink(ApplicationProvider.getApplicationContext(), dagger.Lazy { identity }, verifier, repo, StoreLicenseTokenUseCase(repo))
        val evaluate = EvaluateLicenseStatusUseCase(repo, verifier, identity)

        val bundle = Bundle().apply { putString(ManagedLicenseTokenPolicy.RESTRICTION_KEY, f.getString("license_token")) }
        link.syncFromRestrictions(bundle)
        assertEquals(EffectiveLicenseStatus.OPERATING, evaluate(System.currentTimeMillis()))

        // O mesmo token num aparelho com outro identificador nunca é aceito.
        val otherRepo = FakeLicenseRepository()
        val otherIdentity = FakeDeviceIdentityProvider("ff".repeat(32))
        ManagedLicenseLink(ApplicationProvider.getApplicationContext(), dagger.Lazy { otherIdentity }, verifier, otherRepo, StoreLicenseTokenUseCase(otherRepo))
            .syncFromRestrictions(bundle)
        assertEquals(EffectiveLicenseStatus.NOT_ACTIVATED, EvaluateLicenseStatusUseCase(otherRepo, verifier, otherIdentity)(System.currentTimeMillis()))
        println("INTEGRACAO_PB_GATEWAY_OK")
    }
}
