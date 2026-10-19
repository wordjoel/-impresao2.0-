package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.error.InvalidLabelTemplateException
import br.com.atos.printbridge.domain.error.LabelTemplateNotFoundException
import br.com.atos.printbridge.domain.error.NoDefaultLabelTemplateException
import br.com.atos.printbridge.domain.model.LicenseState
import br.com.atos.printbridge.domain.model.PrintJobStatus
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.model.PrinterLanguage
import br.com.atos.printbridge.domain.model.PrinterModel
import br.com.atos.printbridge.domain.model.PrinterProtocol
import br.com.atos.printbridge.domain.model.label.BuiltInLabelTemplates
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelElementType
import br.com.atos.printbridge.domain.model.label.LabelField
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.printing.label.ZplLabelRenderer
import br.com.atos.printbridge.testutil.FakeLabelTemplateRepository
import br.com.atos.printbridge.testutil.FakeLicenseRepository
import br.com.atos.printbridge.testutil.FakePrintJobRepository
import br.com.atos.printbridge.testutil.FakePrinterRepository
import br.com.atos.printbridge.testutil.FakeQueueDispatcher
import br.com.atos.printbridge.testutil.FakeSettingsRepository
import br.com.atos.printbridge.testutil.TestLicenseTokenSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

private val defaultPrinter = Printer(
    id = "printer-1",
    storeId = null,
    name = "Expedição 01",
    model = PrinterModel.TSC_MB241,
    ipAddress = "192.168.0.50",
    port = 9100,
    protocol = PrinterProtocol.TCP,
    language = PrinterLanguage.ZPL,
    isDefault = true,
    enabled = true,
    lastSeenAt = null,
    createdAt = 0,
    updatedAt = 0,
)

class LabelUseCasesTest {

    private val repository = FakeLabelTemplateRepository()

    private fun element(
        id: String = "e1",
        type: LabelElementType = LabelElementType.CAMPO,
        field: LabelField? = LabelField.PRODUTO,
        xMm: Float = 1f,
        yMm: Float = 1f,
        widthMm: Float = 20f,
        heightMm: Float = 5f,
    ) = LabelElement(
        id = id,
        type = type,
        xMm = xMm,
        yMm = yMm,
        widthMm = widthMm,
        heightMm = heightMm,
        field = field,
    )

    private fun template(
        id: String = "t1",
        name: String = "Etiqueta de teste",
        widthMm: Int = 40,
        heightMm: Int = 30,
        dpi: Int = LabelTemplate.DEFAULT_DPI,
        elements: List<LabelElement> = listOf(element()),
        isBuiltIn: Boolean = false,
        isDefault: Boolean = false,
        createdAt: Long = 0L,
    ) = LabelTemplate(
        id = id,
        name = name,
        widthMm = widthMm,
        heightMm = heightMm,
        dpi = dpi,
        elements = elements,
        isBuiltIn = isBuiltIn,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = 0L,
    )

    private fun save() = SaveLabelTemplateUseCase(repository)
    private fun get() = GetLabelTemplateUseCase(repository)
    private fun render() = RenderLabelZplUseCase(repository, ZplLabelRenderer())

    // ---------------------------------------------------------------- salvar / validar

    @Test
    fun `save trims the name and stamps timestamps`() = runTest {
        val saved = save()(template(name = "  Etiqueta nova  "))

        assertEquals("Etiqueta nova", saved.name)
        assertTrue(saved.createdAt > 0)
        assertTrue(saved.updatedAt > 0)
        assertEquals("Etiqueta nova", repository.snapshot.single().name)
    }

    @Test
    fun `save preserves the original createdAt when updating`() = runTest {
        val original = template(createdAt = 1234L)

        val saved = save()(original)

        assertEquals(1234L, saved.createdAt)
    }

    @Test
    fun `save rejects a blank name`() = runTest {
        assertThrows(InvalidLabelTemplateException::class.java) {
            runBlocking { save()(template(name = "   ")) }
        }
    }

    @Test
    fun `save rejects an element that leaves the label`() = runTest {
        val outOfBounds = element(xMm = 35f, widthMm = 20f) // 35 + 20 > 40

        assertThrows(InvalidLabelTemplateException::class.java) {
            runBlocking { save()(template(elements = listOf(outOfBounds))) }
        }
    }

    @Test
    fun `save rejects a campo element without a field`() = runTest {
        val withoutField = element(type = LabelElementType.CAMPO, field = null)

        assertThrows(InvalidLabelTemplateException::class.java) {
            runBlocking { save()(template(elements = listOf(withoutField))) }
        }
    }

    @Test
    fun `save rejects an unsupported dpi`() = runTest {
        assertThrows(InvalidLabelTemplateException::class.java) {
            runBlocking { save()(template(dpi = 600)) }
        }
    }

    @Test
    fun `save rejects a label smaller than the minimum`() = runTest {
        assertThrows(InvalidLabelTemplateException::class.java) {
            runBlocking { save()(template(widthMm = 5, heightMm = 5)) }
        }
    }

    // ---------------------------------------------------------------- consulta / padrão

    @Test
    fun `get throws when the template does not exist`() = runTest {
        assertThrows(LabelTemplateNotFoundException::class.java) {
            runBlocking { get()("nao-existe") }
        }
    }

    @Test
    fun `get default throws when nothing is marked as default`() = runTest {
        repository.upsert(template())

        assertThrows(NoDefaultLabelTemplateException::class.java) {
            runBlocking { GetDefaultLabelTemplateUseCase(repository)() }
        }
    }

    @Test
    fun `set default keeps exactly one default`() = runTest {
        repository.upsert(template(id = "t1", isDefault = true))
        repository.upsert(template(id = "t2"))

        SetDefaultLabelTemplateUseCase(repository)("t2")

        assertEquals("t2", repository.getDefault()?.id)
        assertEquals(1, repository.snapshot.count { it.isDefault })
    }

    @Test
    fun `saving a template marked as default clears the previous default`() = runTest {
        repository.upsert(template(id = "t1", isDefault = true))

        save()(template(id = "t2", isDefault = true))

        assertEquals("t2", repository.getDefault()?.id)
        assertEquals(1, repository.snapshot.count { it.isDefault })
    }

    // ---------------------------------------------------------------- remover / duplicar

    @Test
    fun `delete removes the template and rejects an unknown id`() = runTest {
        repository.upsert(template(id = "t1"))

        DeleteLabelTemplateUseCase(repository)("t1")

        assertNull(repository.getById("t1"))
        assertThrows(LabelTemplateNotFoundException::class.java) {
            runBlocking { DeleteLabelTemplateUseCase(repository)("t1") }
        }
    }

    @Test
    fun `duplicate creates an independent copy that is neither default nor built in`() = runTest {
        repository.upsert(template(id = "t1", isBuiltIn = true, isDefault = true))

        val copy = DuplicateLabelTemplateUseCase(repository)("t1")

        assertNotEquals("t1", copy.id)
        assertEquals("Etiqueta de teste (cópia)", copy.name)
        assertEquals(false, copy.isDefault)
        assertEquals(false, copy.isBuiltIn)
        assertNotEquals(repository.getById("t1")!!.elements.first().id, copy.elements.first().id)
        assertEquals(2, repository.snapshot.size)
    }

    // ---------------------------------------------------------------- semeadura

    @Test
    fun `ensure built in seeds only once and never overwrites the operator edition`() = runTest {
        val ensure = EnsureBuiltInLabelsUseCase(repository)

        assertEquals(3, ensure())
        assertEquals(0, ensure())
        assertEquals(3, repository.count())

        val edited = repository.getById(BuiltInLabelTemplates.PRICE_TEMPLATE_ID)!!.copy(name = "Meu preço")
        repository.upsert(edited)

        ensure()

        assertEquals("Meu preço", repository.getById(BuiltInLabelTemplates.PRICE_TEMPLATE_ID)?.name)
    }

    // ---------------------------------------------------------------- render

    @Test
    fun `render by id and by template produce the same zpl`() = runTest {
        repository.upsert(template())
        val data = LabelData(mapOf(LabelField.PRODUTO to "CAFE"))

        val byId = render()("t1", data)
        val byTemplate = render()(repository.getById("t1")!!, data)

        assertEquals(byId, byTemplate)
        assertTrue(byId.contains("^FDCAFE^FS"))
    }

    @Test
    fun `render by unknown id throws`() = runTest {
        assertThrows(LabelTemplateNotFoundException::class.java) {
            runBlocking { render()("nao-existe") }
        }
    }

    // ---------------------------------------------------------------- impressão (entrega à fila existente)

    private fun printUseCase(): Triple<PrintLabelUseCase, FakePrintJobRepository, FakeQueueDispatcher> {
        val printerRepository = FakePrinterRepository().apply { seed(defaultPrinter) }
        val jobRepository = FakePrintJobRepository()
        val dispatcher = FakeQueueDispatcher()
        val enqueue = EnqueuePrintJobUseCase(
            jobRepository,
            printerRepository,
            DuplicateSubmissionGuard(),
            dispatcher,
            EvaluateLicenseStatusUseCase(
                FakeLicenseRepository(LicenseState(rawToken = TestLicenseTokenSigner.sign(validUntil = Long.MAX_VALUE / 2))),
                TestLicenseTokenSigner.createVerifier(),
                TestLicenseTokenSigner.deviceIdentity(),
            ),
            FakeSettingsRepository(),
        )
        return Triple(PrintLabelUseCase(repository, ZplLabelRenderer(), enqueue), jobRepository, dispatcher)
    }

    @Test
    fun `print enqueues a zpl job on the existing queue and reports the job back`() = runTest {
        repository.upsert(template(name = "Preço 40x30"))
        val (print, jobs, dispatcher) = printUseCase()

        val job = print("t1", LabelData(), printerId = null, copies = 1)

        assertEquals(1, jobs.snapshot.size)
        assertEquals(1, dispatcher.requestCount)
        assertEquals(PrintPayloadType.ZPL, job.type)
        assertEquals(PrintJobStatus.PENDING, job.status)
        assertEquals("Etiqueta: Preço 40x30", job.documentName)
        assertEquals("t1", job.externalDocumentId)
        assertEquals(defaultPrinter.id, job.printerId)
        assertTrue(job.payload.contains("^XA"))
        assertNotNull(jobs.snapshot.first { it.id == job.id })
    }

    @Test
    fun `print forwards the requested copies`() = runTest {
        repository.upsert(template())
        val (print, jobs, _) = printUseCase()

        val job = print("t1", LabelData(), printerId = null, copies = 3)

        assertEquals(3, job.copies)
        assertEquals(3, jobs.snapshot.single().copies)
    }

    @Test
    fun `print by unknown template throws before touching the queue`() = runTest {
        val (print, jobs, dispatcher) = printUseCase()

        assertThrows(LabelTemplateNotFoundException::class.java) {
            runBlocking { print("nao-existe", LabelData(), null, 1) }
        }
        assertTrue(jobs.snapshot.isEmpty())
        assertEquals(0, dispatcher.requestCount)
    }
}
