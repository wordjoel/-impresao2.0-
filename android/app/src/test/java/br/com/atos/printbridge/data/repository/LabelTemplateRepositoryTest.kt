package br.com.atos.printbridge.data.repository

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.atos.printbridge.data.local.db.AppDatabase
import br.com.atos.printbridge.domain.model.label.BarcodeType
import br.com.atos.printbridge.domain.model.label.LabelAlign
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelField
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.repository.LabelTemplateRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room de verdade (em memória, via Robolectric) para o módulo de etiquetas: prova que o layout
 * serializado numa única coluna `TEXT` volta íntegro e que a invariante "no máximo um padrão" vale
 * no banco — não apenas no fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LabelTemplateRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LabelTemplateRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LabelTemplateRepositoryImpl(db.labelTemplateDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun element(
        id: String,
        type: LabelElementType = LabelElementType.CAMPO,
        field: LabelField? = LabelField.PRODUTO,
        text: String = "",
    ) = LabelElement(
        id = id,
        type = type,
        xMm = 1.5f,
        yMm = 2f,
        widthMm = 20f,
        heightMm = 5f,
        text = text,
        field = field,
        barcodeType = BarcodeType.EAN13,
        rotationDeg = 90,
        align = LabelAlign.CENTER,
        fontSizeMm = 4f,
        thicknessMm = 0.6f,
    )

    private fun template(
        id: String = "t1",
        name: String = "Etiqueta",
        elements: List<LabelElement> = listOf(element("e1")),
        isDefault: Boolean = false,
        isBuiltIn: Boolean = false,
    ) = LabelTemplate(
        id = id,
        name = name,
        widthMm = 40,
        heightMm = 30,
        elements = elements,
        isBuiltIn = isBuiltIn,
        isDefault = isDefault,
        createdAt = 100,
        updatedAt = 200,
    )

    @Test
    fun `elements survive the round trip through the layout column`() = runTest {
        val original = template(
            elements = listOf(
                element("e1", type = LabelElementType.TEXTO, field = null, text = "Ola\tmundo\nlinha 2"),
                element("e2", type = LabelElementType.CODIGO_BARRAS, field = LabelField.CODIGO),
                element("e3", type = LabelElementType.QRCODE, field = LabelField.LOJA),
                element("e4", type = LabelElementType.CAIXA, field = null),
            ),
        )

        repository.upsert(original)

        assertEquals(original, repository.getById("t1"))
    }

    @Test
    fun `upsert replaces the row with the same id`() = runTest {
        repository.upsert(template(name = "Antiga"))
        repository.upsert(template(name = "Nova"))

        assertEquals("Nova", repository.getById("t1")?.name)
        assertEquals(1, repository.count())
    }

    @Test
    fun `observeAll orders by name`() = runTest {
        repository.upsert(template(id = "t1", name = "Zebra"))
        repository.upsert(template(id = "t2", name = "Alpha"))

        assertEquals(listOf("Alpha", "Zebra"), repository.observeAll().first().map { it.name })
    }

    @Test
    fun `getDefault returns null when nothing is marked`() = runTest {
        repository.upsert(template())

        assertNull(repository.getDefault())
    }

    @Test
    fun `setDefault marks only the target`() = runTest {
        repository.upsert(template(id = "t1", isDefault = true))
        repository.upsert(template(id = "t2"))

        repository.setDefault("t2")

        assertEquals("t2", repository.getDefault()?.id)
        assertEquals(1, repository.observeAll().first().count { it.isDefault })
    }

    @Test
    fun `upserting a default clears the previous default`() = runTest {
        repository.upsert(template(id = "t1", isDefault = true))

        repository.upsert(template(id = "t2", isDefault = true))

        val stored = repository.observeAll().first()
        assertEquals(1, stored.count { it.isDefault })
        assertEquals("t2", repository.getDefault()?.id)
    }

    @Test
    fun `delete removes only the target`() = runTest {
        repository.upsert(template(id = "t1"))
        repository.upsert(template(id = "t2"))

        repository.delete("t1")

        assertNull(repository.getById("t1"))
        assertTrue(repository.getById("t2") != null)
        assertEquals(1, repository.count())
    }
}
