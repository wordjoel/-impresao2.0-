package br.com.atos.printbridge.di

import br.com.atos.printbridge.domain.model.label.LabelZplRenderer
import br.com.atos.printbridge.printing.label.ZplLabelRenderer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Amarra a renderização de etiqueta à implementação ZPL (TSC). Note o que este módulo **não** faz:
 * não provê transporte, fila nem worker para etiquetas — a impressão usa exatamente o core
 * existente (`PrintingModule`/`EnqueuePrintJobUseCase`), evitando um segundo motor de impressão.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LabelModule {

    @Binds
    @Singleton
    abstract fun bindLabelZplRenderer(impl: ZplLabelRenderer): LabelZplRenderer
}
