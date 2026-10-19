package br.com.atos.printbridge.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testa as migrations Room v1→v5 com bancos REAIS criados à mão (mesmas tabelas e identity hash dos
 * schemas exportados em `app/schemas/.../AppDatabase/`). Garante que instalações existentes não
 * perdem dados ao abrir cada versão nova — o risco CRÍTICO apontado no audit
 * (CURRENT-PRINT-FLOW-AUDIT.md §7). Nunca usar fallbackToDestructiveMigration.
 *
 * Nota: manter o nº de testes (arquivos) pequeno — abrir muitos bancos file-based numa classe
 * satura a lib nativa de sqlite do Robolectric (SQLiteException: unable to open database file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AppDatabaseMigrationTest {

    private lateinit var context: Context
    private var dbName: String = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "migration-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Cria um banco file-based na versão exata do schema exportado daquela fase. */
    private fun createDatabase(versionFrom: Int, includeDevice: Boolean) {
        val dbFile = context.getDatabasePath(dbName)
        // `openOrCreateDatabase` exige o diretório `databases/` já criado (CREATE_IF_NECESSARY não
        // cria diretórios pais) — no Robolectric o dataDir é um temp novo por teste.
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).use { sqlite ->
            // A tabela printers muda de forma na v4 (storeId/lastSeenAt), então o DDL precisa ser o
            // da versão de origem — senão a migration 3→4 tentaria adicionar coluna existente.
            sqlite.execSQL(if (versionFrom >= 4) PRINTERS_V4 else PRINTERS_V1)
            sqlite.execSQL(PRINT_JOBS_V1)
            if (versionFrom >= 2) sqlite.execSQL(STORES_V2)
            if (versionFrom >= 3) sqlite.execSQL(DEVICES_V3)
            if (versionFrom >= 5) sqlite.execSQL(LABEL_TEMPLATES_V5)
            if (versionFrom >= 6) sqlite.execSQL("ALTER TABLE print_jobs ADD COLUMN validationWarnings TEXT")
            sqlite.execSQL(
                "INSERT INTO printers (id, name, model, ipAddress, port, protocol, language, isDefault, enabled, createdAt, updatedAt) " +
                    "VALUES ('printer-1', 'Expedição 01', 'TSC_MB241', '192.168.0.50', 9100, 'TCP', 'ZPL', 1, 1, 100, 100)"
            )
            sqlite.execSQL(
                "INSERT INTO print_jobs (id, printerId, type, payload, documentName, copies, status, attempts, maxAttempts, errorMessage, externalDocumentId, originalJobId, createdAt, startedAt, sentAt, updatedAt) " +
                    "VALUES ('job-1', 'printer-1', 'ZPL', '^XA^XZ', 'etiqueta', 1, 'SENT', 1, 3, NULL, NULL, NULL, 100, 100, 150, 150)"
            )
            if (versionFrom >= 2) {
                sqlite.execSQL(
                    "INSERT INTO stores (storeId, storeCode, storeName, status, activationStatus, createdAt, updatedAt) " +
                        "VALUES ('store-1', '4521', 'Loja Centro', 'UNKNOWN', 'PENDING', 100, 100)"
                )
            }
            if (includeDevice) {
                sqlite.execSQL(
                    "INSERT INTO devices (deviceId, storeId, deviceName, manufacturer, model, osVersion, appVersion, status, lastSeenAt, activatedAt) " +
                        "VALUES ('device-1', 'store-1', 'Caixa 1', 'Sunmi', 'V2 Pro', '9', '1.0.0', 'REGISTERED', NULL, NULL)"
                )
            }
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            val identityHash = when (versionFrom) {
                1 -> IDENTITY_HASH_V1
                2 -> IDENTITY_HASH_V2
                3 -> IDENTITY_HASH_V3
                4 -> IDENTITY_HASH_V4
                5 -> IDENTITY_HASH_V5
                6 -> IDENTITY_HASH_V6
                else -> IDENTITY_HASH_V7
            }
            sqlite.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$identityHash')")
            sqlite.version = versionFrom
        }
    }

    private suspend fun assertPrinterAndJobPreserved(db: AppDatabase) {
        val printer = db.printerDao().getById("printer-1")
        val job = db.printJobDao().getById("job-1")
        assertNotNull(printer)
        assertEquals("Expedição 01", printer?.name)
        assertEquals(true, printer?.isDefault)
        assertNotNull(job)
        assertEquals("SENT", job?.status)
        // v6: jobs anteriores à validação P1.3 migram sem avisos (coluna nullable, nunca inventada).
        assertNull(job?.validationWarnings)
        // v7: impressora existente ganha o perfil de mídia padrão; job antigo não pertence a grupo.
        assertEquals(Triple(203, 100, 72), Triple(printer?.dpi, printer?.labelWidthMm, printer?.labelHeightMm))
        assertNull(job?.groupId)
        assertNull(job?.pageIndex)
        // v9: origem do job antigo fica vazia — nunca inventada.
        assertNull(job?.storeId)
        assertNull(job?.collectorId)
        assertNull(job?.deviceId)
        assertNull(job?.sourceApp)
        assertNull(job?.operatorName)
    }

    /** A tabela nova da v5 precisa existir e aceitar escrita depois de qualquer caminho de migration. */
    private suspend fun assertLabelTemplatesUsable(db: AppDatabase) {
        val dao = db.labelTemplateDao()
        dao.upsert(
            br.com.atos.printbridge.data.local.entity.LabelTemplateEntity(
                id = "label-1",
                name = "Etiqueta migrada",
                widthMm = 40,
                heightMm = 30,
                dpi = 203,
                layout = "e1\tTEXTO\t0\t0\t20\t5\tOla\t\tCODE128\t0\tLEFT\t3\t0.5",
                isBuiltIn = false,
                isDefault = true,
                createdAt = 100,
                updatedAt = 100,
            )
        )
        assertEquals("Etiqueta migrada", dao.getById("label-1")?.name)
        assertEquals("label-1", dao.getDefault()?.id)
        assertEquals(1, dao.count())
    }

    @Test
    fun `migration v1 to v8 keeps data and adds every new table`() = runTest {
        createDatabase(versionFrom = 1, includeDevice = false)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
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
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()

        assertPrinterAndJobPreserved(db)
        val printer = db.printerDao().getById("printer-1")
        assertNull(printer?.storeId)
        assertNull(printer?.lastSeenAt)

        db.storeDao().upsert(
            br.com.atos.printbridge.data.local.entity.StoreEntity(
                storeId = "store-1",
                storeCode = "4521",
                storeName = "Loja Centro",
                status = "UNKNOWN",
                activationStatus = "PENDING",
                createdAt = 100,
                updatedAt = 100,
            )
        )
        assertNotNull(db.storeDao().getById("store-1"))
        db.deviceDao().upsert(
            br.com.atos.printbridge.data.local.entity.DeviceEntity(
                deviceId = "device-keystore-hash",
                storeId = "store-1",
                deviceName = "Caixa 2",
                manufacturer = "Sunmi",
                model = "V2 Pro",
                osVersion = "9",
                appVersion = "1.0.0",
                status = "REGISTERED",
                lastSeenAt = null,
                activatedAt = null,
            )
        )
        assertNotNull(db.deviceDao().getById("device-keystore-hash"))
        assertLabelTemplatesUsable(db)

        db.close()
    }

    @Test
    fun `migration v4 to v8 keeps data and adds v5-v8 columns`() = runTest {
        createDatabase(versionFrom = 4, includeDevice = true)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()

        assertPrinterAndJobPreserved(db)
        assertNotNull(db.storeDao().getById("store-1"))
        assertEquals("store-1", db.deviceDao().getById("device-1")?.storeId)
        val printer = db.printerDao().getById("printer-1")
        assertNotNull(printer)
        db.printerDao().upsert(printer!!.copy(storeId = "store-1", lastSeenAt = 1234))
        assertEquals("store-1", db.printerDao().getById("printer-1")?.storeId)
        // v8: colunas de identidade descoberta nascem NULL (nada presumido) e aceitam o que a impressora informar.
        assertEquals(null, db.printerDao().getById("printer-1")?.manufacturer)
        db.printerDao().upsert(db.printerDao().getById("printer-1")!!.copy(manufacturer = "Samsung", modelName = "M2070 Series", ippPath = "ipp/print", capabilities = "formats=image/pwg-raster"))
        assertEquals("M2070 Series", db.printerDao().getById("printer-1")?.modelName)
        assertEquals("ipp/print", db.printerDao().getById("printer-1")?.ippPath)
        assertLabelTemplatesUsable(db)

        db.close()
    }

    private companion object {
        const val IDENTITY_HASH_V1 = "cd6ac53d99f02f2b720cb46e18273fef"
        const val IDENTITY_HASH_V2 = "464f743715391b81299aaa7192467dda"
        const val IDENTITY_HASH_V3 = "ab7e174d127df81220e5acdee0ce5f35"
        const val IDENTITY_HASH_V4 = "6c19109589ec02fdbe0db059ef316fe5"
        const val IDENTITY_HASH_V5 = "d83d138d353ab455acce19ed90a2cdc9"
        const val IDENTITY_HASH_V6 = "dfe0dbb52e5ac97f060429d422cefe4f"
        const val IDENTITY_HASH_V7 = "eb8e87b200a972a7e2f57918f575e9f7"

        val PRINTERS_V1 = """
            CREATE TABLE IF NOT EXISTS `printers` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `model` TEXT NOT NULL,
                `ipAddress` TEXT NOT NULL, `port` INTEGER NOT NULL, `protocol` TEXT NOT NULL,
                `language` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
        """.trimIndent()

        val PRINT_JOBS_V1 = """
            CREATE TABLE IF NOT EXISTS `print_jobs` (
                `id` TEXT NOT NULL, `printerId` TEXT NOT NULL, `type` TEXT NOT NULL,
                `payload` TEXT NOT NULL, `documentName` TEXT NOT NULL, `copies` INTEGER NOT NULL,
                `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `maxAttempts` INTEGER NOT NULL,
                `errorMessage` TEXT, `externalDocumentId` TEXT, `originalJobId` TEXT,
                `createdAt` INTEGER NOT NULL, `startedAt` INTEGER, `sentAt` INTEGER,
                `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
        """.trimIndent()

        val STORES_V2 = """
            CREATE TABLE IF NOT EXISTS `stores` (
                `storeId` TEXT NOT NULL, `storeCode` TEXT NOT NULL, `storeName` TEXT NOT NULL,
                `status` TEXT NOT NULL, `activationStatus` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeId`)
            )
        """.trimIndent()

        val DEVICES_V3 = """
            CREATE TABLE IF NOT EXISTS `devices` (
                `deviceId` TEXT NOT NULL, `storeId` TEXT, `deviceName` TEXT NOT NULL,
                `manufacturer` TEXT NOT NULL, `model` TEXT NOT NULL, `osVersion` TEXT NOT NULL,
                `appVersion` TEXT NOT NULL, `status` TEXT NOT NULL, `lastSeenAt` INTEGER,
                `activatedAt` INTEGER, PRIMARY KEY(`deviceId`)
            )
        """.trimIndent()

        // DDL exato de app/schemas/.../4.json (printers ganhou storeId/lastSeenAt na v4).
        val PRINTERS_V4 = """
            CREATE TABLE IF NOT EXISTS `printers` (
                `id` TEXT NOT NULL, `storeId` TEXT, `name` TEXT NOT NULL, `model` TEXT NOT NULL,
                `ipAddress` TEXT NOT NULL, `port` INTEGER NOT NULL, `protocol` TEXT NOT NULL,
                `language` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
                `lastSeenAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent()

        // DDL exato de app/schemas/.../5.json.
        val LABEL_TEMPLATES_V5 = """
            CREATE TABLE IF NOT EXISTS `label_templates` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `widthMm` INTEGER NOT NULL,
                `heightMm` INTEGER NOT NULL, `dpi` INTEGER NOT NULL, `layout` TEXT NOT NULL,
                `isBuiltIn` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
        """.trimIndent()
    }
}