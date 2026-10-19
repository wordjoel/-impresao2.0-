package br.com.atos.printbridge.di

import br.com.atos.printbridge.BuildConfig
import br.com.atos.printbridge.data.datastore.LicenseRepositoryImpl
import br.com.atos.printbridge.domain.license.DeviceIdentityProvider
import br.com.atos.printbridge.domain.license.EcdsaLicenseTokenVerifier
import br.com.atos.printbridge.domain.license.LicenseTokenVerifier
import br.com.atos.printbridge.domain.repository.LicenseRepository
import br.com.atos.printbridge.domain.queue.JobOriginProvider
import br.com.atos.printbridge.license.AndroidKeystoreDeviceIdentityProvider
import br.com.atos.printbridge.license.KeystoreJobOriginProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Base64
import javax.inject.Singleton

/**
 * Chave pública do servidor de licenciamento, embutida no binário do app — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
 * Precisa ser fixa em tempo de build (nunca obtida em runtime/rede), senão um MITM poderia
 * substituí-la e assinar tokens falsos com a própria chave do atacante.
 *
 * PRODUÇÃO (2026-10-05): o backend de licenciamento é o gateway MDM (mdm-device-gateway), que
 * assina o License Token com a chave privada guardada como segredo LICENSE_TOKEN_SIGNING_KEY da
 * Edge Function. SHA-256 desta SPKI: 763ff71c65229c1eb8bbd3f66d19be2ffb0d7e881ef662ae83d0c7601c0dd641.
 * O Windows Bridge deve usar o MESMO valor — as duas plataformas verificam contra a mesma chave
 * do mesmo servidor. A chave de TESTE (§6) só existe nos testes (TestLicenseTokenSigner).
 */
private const val PRODUCTION_SERVER_PUBLIC_KEY_SPKI_BASE64 =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgSg23NVPPeBmep5ZUXWKsE7ZXlSo+ixnVzsLv79bqAYbQV7+" +
        "xoU38FcmWfDLsMvbNaZJpDse60zkrcJU/y/afA=="

@Module
@InstallIn(SingletonComponent::class)
abstract class LicenseModule {

    @Binds
    @Singleton
    abstract fun bindLicenseRepository(impl: LicenseRepositoryImpl): LicenseRepository

    @Binds
    @Singleton
    abstract fun bindDeviceIdentityProvider(impl: AndroidKeystoreDeviceIdentityProvider): DeviceIdentityProvider

    @Binds
    abstract fun bindJobOriginProvider(impl: KeystoreJobOriginProvider): JobOriginProvider

    companion object {
        @Provides
        @Singleton
        fun provideLicenseTokenVerifier(): LicenseTokenVerifier =
            EcdsaLicenseTokenVerifier(
                Base64.getDecoder().decode(
                    selectLicensePublicKey(BuildConfig.DEBUG, BuildConfig.LICENSE_TEST_PUBLIC_KEY_SPKI_B64),
                ),
            )
    }
}

/**
 * D-EMU-1 (homologação em emulador, 14/10/2026): só a variante DEBUG pode trocar a chave pública
 * pela chave de TESTE do gateway local (passada em -Ppb.debug.licenseTestPublicKey; só a PÚBLICA).
 * Release usa SEMPRE a de produção: o campo é vazio na release e o Gradle falha se a propriedade
 * vier junto com qualquer tarefa de release.
 */
internal fun selectLicensePublicKey(isDebugBuild: Boolean, testPublicKeyB64: String): String =
    if (isDebugBuild && testPublicKeyB64.isNotBlank()) testPublicKeyB64 else PRODUCTION_SERVER_PUBLIC_KEY_SPKI_BASE64

/** Exposta só para o teste de separação das variantes. */
internal val productionLicensePublicKeyForTest: String get() = PRODUCTION_SERVER_PUBLIC_KEY_SPKI_BASE64
