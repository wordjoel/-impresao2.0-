package br.com.atos.printbridge.license

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import br.com.atos.printbridge.application.usecase.EvaluateLicenseStatusUseCase
import br.com.atos.printbridge.application.usecase.StoreLicenseTokenUseCase
import br.com.atos.printbridge.application.usecase.EffectiveLicenseStatus
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.LicenseTokenStatus
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric (Bundle real do Android; sem aparelho): o License Token da configuração gerenciada
 * passa pela regra e chega à avaliação de sempre, que decide se o app imprime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ManagedLicenseLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val verifier = TestLicenseTokenSigner.createVerifier()
    private val identity = TestLicenseTokenSigner.deviceIdentity()
    private val now = 1_100_000L // dentro da validade do token de teste

    private fun link(repo: FakeLicenseRepository) =
        ManagedLicenseLink(context, dagger.Lazy { identity }, verifier, repo, StoreLicenseTokenUseCase(repo))

    private fun restrictions(token: String?) = Bundle().apply {
        putString("store_code", "LOJA-TESTE") // configuração de loja convive na mesma Bundle
        token?.let { putString(ManagedLicenseTokenPolicy.RESTRICTION_KEY, it) }
    }

    @Test
    fun `token entregue pelo agente libera a impressao (OPERATING)`() = runTest {
        val repo = FakeLicenseRepository()
        val evaluate = EvaluateLicenseStatusUseCase(repo, verifier, identity)
        assertEquals(EffectiveLicenseStatus.NOT_ACTIVATED, evaluate(now))
        link(repo).syncFromRestrictions(restrictions(TestLicenseTokenSigner.sign()))
        assertEquals(EffectiveLicenseStatus.OPERATING, evaluate(now))
    }

    @Test
    fun `configuracao sem token, corrompida ou de outro aparelho mantem a licenca guardada`() = runTest {
        val good = TestLicenseTokenSigner.sign()
        val repo = FakeLicenseRepository(LicenseState(rawToken = good))
        val l = link(repo)
        l.syncFromRestrictions(restrictions(null))
        l.syncFromRestrictions(restrictions("corrompido.abc"))
        l.syncFromRestrictions(restrictions(TestLicenseTokenSigner.sign(deviceId = "outro-coletor")))
        l.syncFromRestrictions(null)
        assertEquals(good, repo.current().rawToken)
        assertEquals(EffectiveLicenseStatus.OPERATING, EvaluateLicenseStatusUseCase(repo, verifier, identity)(now))
    }

    @Test
    fun `revogacao entregue pelo agente bloqueia novos trabalhos (RESTRICTED)`() = runTest {
        val repo = FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign()))
        link(repo).syncFromRestrictions(restrictions(TestLicenseTokenSigner.sign(status = LicenseTokenStatus.REVOKED)))
        assertEquals(EffectiveLicenseStatus.RESTRICTED, EvaluateLicenseStatusUseCase(repo, verifier, identity)(now))
    }

    @Test
    fun `sem nenhum token o app continua NOT_ACTIVATED, sem erro`() = runTest {
        val repo = FakeLicenseRepository()
        link(repo).syncFromRestrictions(Bundle())
        assertNull(repo.current().rawToken)
        assertEquals(EffectiveLicenseStatus.NOT_ACTIVATED, EvaluateLicenseStatusUseCase(repo, verifier, identity)(now))
    }

    @Test
    fun `trabalhos herdados do agente embutido tem os nomes unicos da versao anterior`() {
        assertEquals(listOf("mdm_heartbeat_periodic", "mdm_heartbeat_onetime"), ManagedLicenseLink.LEGACY_EMBEDDED_AGENT_WORK)
    }
}
