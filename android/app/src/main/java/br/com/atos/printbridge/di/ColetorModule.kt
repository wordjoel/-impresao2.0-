package br.com.atos.printbridge.di

import br.com.atos.printbridge.application.coletor.ColetorLocalStore
import br.com.atos.printbridge.application.coletor.ColetorRemote
import br.com.atos.printbridge.application.coletor.ColetorTemplateResolver
import br.com.atos.printbridge.application.coletor.DesignerTemplateSource
import br.com.atos.printbridge.application.coletor.DeviceClock
import br.com.atos.printbridge.data.coletor.AndroidColetorStore
import br.com.atos.printbridge.data.coletor.AndroidDeviceClock
import br.com.atos.printbridge.data.coletor.ColetorHttpRemote
import br.com.atos.printbridge.data.coletor.KeystoreSecretBox
import br.com.atos.printbridge.data.coletor.SecretBox
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Passo 6: templates publicados pelo designer do PC, usados pela ponte (printJob TEMPLATE). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ColetorModule {
    @Binds abstract fun designerTemplates(impl: ColetorTemplateResolver): DesignerTemplateSource
    @Binds abstract fun localStore(impl: AndroidColetorStore): ColetorLocalStore
    @Binds abstract fun remote(impl: ColetorHttpRemote): ColetorRemote
    @Binds abstract fun clock(impl: AndroidDeviceClock): DeviceClock
    @Binds abstract fun secrets(impl: KeystoreSecretBox): SecretBox
}
