package br.com.atos.printbridge.di

import android.content.Context
import androidx.room.Room
import br.com.atos.printbridge.data.local.dao.DeviceDao
import br.com.atos.printbridge.data.local.dao.LabelTemplateDao
import br.com.atos.printbridge.data.local.dao.PrinterDao
import br.com.atos.printbridge.data.local.dao.PrintJobDao
import br.com.atos.printbridge.data.local.dao.StoreDao
import br.com.atos.printbridge.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .build()

    @Provides
    fun providePrinterDao(db: AppDatabase): PrinterDao = db.printerDao()

    @Provides
    fun providePrintJobDao(db: AppDatabase): PrintJobDao = db.printJobDao()

    @Provides
    fun provideStoreDao(db: AppDatabase): StoreDao = db.storeDao()

    @Provides
    fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideLabelTemplateDao(db: AppDatabase): LabelTemplateDao = db.labelTemplateDao()
}