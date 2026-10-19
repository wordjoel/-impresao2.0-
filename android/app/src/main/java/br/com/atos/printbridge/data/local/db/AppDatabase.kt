package br.com.atos.printbridge.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.atos.printbridge.data.local.dao.DeviceDao
import br.com.atos.printbridge.data.local.dao.LabelTemplateDao
import br.com.atos.printbridge.data.local.dao.PrinterDao
import br.com.atos.printbridge.data.local.dao.PrintJobDao
import br.com.atos.printbridge.data.local.dao.StoreDao
import br.com.atos.printbridge.data.local.entity.DeviceEntity
import br.com.atos.printbridge.data.local.entity.LabelTemplateEntity
import br.com.atos.printbridge.data.local.entity.PrinterEntity
import br.com.atos.printbridge.data.local.entity.PrintJobEntity
import br.com.atos.printbridge.data.local.entity.StoreEntity

@Database(
    entities = [
        PrinterEntity::class,
        PrintJobEntity::class,
        StoreEntity::class,
        DeviceEntity::class,
        LabelTemplateEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printerDao(): PrinterDao
    abstract fun printJobDao(): PrintJobDao
    abstract fun storeDao(): StoreDao
    abstract fun deviceDao(): DeviceDao
    abstract fun labelTemplateDao(): LabelTemplateDao

    companion object {
        const val DATABASE_NAME = "atos_print_bridge.db"

        /**
         * v1→v2 (Fase 2 — modelo por loja): cria a tabela `stores`. Apenas aditiva, preserva
         * printers/print_jobs intactos. Nunca usar fallbackToDestructiveMigration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stores` (
                        `storeId` TEXT NOT NULL,
                        `storeCode` TEXT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `activationStatus` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`storeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2→v3 (Fase 3 — Device): cria a tabela `devices`. Apenas aditiva; preserva
         * printers/print_jobs/stores intactos. Nunca usar fallbackToDestructiveMigration.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `devices` (
                        `deviceId` TEXT NOT NULL,
                        `storeId` TEXT,
                        `deviceName` TEXT NOT NULL,
                        `manufacturer` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `osVersion` TEXT NOT NULL,
                        `appVersion` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastSeenAt` INTEGER,
                        `activatedAt` INTEGER,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3→v4 (Fase 4 — printers por loja): adiciona `storeId` e `lastSeenAt` em `printers`.
         * Apenas aditiva (ALTER TABLE ADD COLUMN), preserva printers/print_jobs/stores/devices
         * intactos. Nunca usar fallbackToDestructiveMigration.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `storeId` TEXT")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `lastSeenAt` INTEGER")
            }
        }

        /**
         * v4→v5 (módulo de etiquetas): cria a tabela `label_templates`. Apenas aditiva; preserva
         * printers/print_jobs/stores/devices intactos. Nunca usar fallbackToDestructiveMigration —
         * um histórico de fila existente não pode ser perdido por causa de uma feature nova.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `label_templates` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `widthMm` INTEGER NOT NULL,
                        `heightMm` INTEGER NOT NULL,
                        `dpi` INTEGER NOT NULL,
                        `layout` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v5→v6 (P1.3 — validação ZPL não bloqueante): adiciona `validationWarnings` em
         * `print_jobs`, para persistir os avisos estruturais de ZPL detectados na entrada junto do
         * job — o operador os vê na fila/histórico antes de confiar no status `SENT`. Apenas
         * aditiva (ALTER TABLE ADD COLUMN nullable), preserva todo o histórico existente. Nunca usar
         * fallbackToDestructiveMigration — um histórico de fila existente não pode ser perdido.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `validationWarnings` TEXT")
            }
        }

        /**
         * v6→v7 (Fase 1 do Print Bridge): perfil de mídia por impressora (dpi e papel padrão, usados
         * pela rasterização de PDF/imagem) e agrupamento de páginas de um documento na fila.
         * Apenas aditiva; impressoras existentes assumem 203dpi e 100x72mm (a etiqueta de referência
         * da TSC ML241P) até o admin ajustar no cadastro.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `dpi` INTEGER NOT NULL DEFAULT 203")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `labelWidthMm` INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `labelHeightMm` INTEGER NOT NULL DEFAULT 72")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `groupId` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `pageIndex` INTEGER")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `pageCount` INTEGER")
            }
        }

        /**
         * v7→v8 (impressoras reais): identidade e capacidades DESCOBERTAS da impressora (fabricante,
         * modelo, hostname, caminho IPP, capacidades IPP) e o retrato da impressora em cada job do
         * histórico. Apenas aditiva e tudo NULL: nada é presumido para impressoras já cadastradas.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `manufacturer` TEXT")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `modelName` TEXT")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `hostname` TEXT")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `ippPath` TEXT")
                db.execSQL("ALTER TABLE `printers` ADD COLUMN `capabilities` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `printerName` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `printerAddress` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `printerProtocol` TEXT")
            }
        }

        /**
         * v8→v9 (Central de Tokens / vários coletores): origem de cada job — loja, coletor, aparelho,
         * app que pediu e operador. Apenas aditiva e tudo NULL: nada é inventado para jobs antigos.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `storeId` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `collectorId` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `deviceId` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `sourceApp` TEXT")
                db.execSQL("ALTER TABLE `print_jobs` ADD COLUMN `operatorName` TEXT")
            }
        }
    }
}