package br.com.atos.printbridge.di

import br.com.atos.printbridge.data.datastore.BrandRepositoryImpl
import br.com.atos.printbridge.data.datastore.SettingsRepositoryImpl
import br.com.atos.printbridge.data.repository.DeviceRepositoryImpl
import br.com.atos.printbridge.data.repository.LabelTemplateRepositoryImpl
import br.com.atos.printbridge.data.repository.PrinterRepositoryImpl
import br.com.atos.printbridge.data.repository.PrintJobRepositoryImpl
import br.com.atos.printbridge.data.repository.StoreRepositoryImpl
import br.com.atos.printbridge.domain.repository.BrandRepository
import br.com.atos.printbridge.domain.repository.DeviceRepository
import br.com.atos.printbridge.domain.repository.LabelTemplateRepository
import br.com.atos.printbridge.domain.repository.PrinterRepository
import br.com.atos.printbridge.domain.repository.PrintJobRepository
import br.com.atos.printbridge.domain.repository.SettingsRepository
import br.com.atos.printbridge.domain.repository.StoreRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPrinterRepository(impl: PrinterRepositoryImpl): PrinterRepository

    @Binds
    @Singleton
    abstract fun bindPrintJobRepository(impl: PrintJobRepositoryImpl): PrintJobRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindStoreRepository(impl: StoreRepositoryImpl): StoreRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindLabelTemplateRepository(impl: LabelTemplateRepositoryImpl): LabelTemplateRepository

    @Binds
    @Singleton
    abstract fun bindBrandRepository(impl: BrandRepositoryImpl): BrandRepository
}