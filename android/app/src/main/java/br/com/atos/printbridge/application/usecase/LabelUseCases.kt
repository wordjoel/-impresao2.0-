package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.queue.JobSource
import br.com.atos.printbridge.domain.error.InvalidLabelTemplateException
import br.com.atos.printbridge.domain.error.LabelTemplateNotFoundException
import br.com.atos.printbridge.domain.error.NoDefaultLabelTemplateException
import br.com.atos.printbridge.domain.model.PrintJob
import br.com.atos.printbridge.domain.model.PrintPayloadType
import br.com.atos.printbridge.domain.model.label.BuiltInLabelTemplates
import br.com.atos.printbridge.domain.model.label.LabelData
import br.com.atos.printbridge.domain.model.label.LabelElement
import br.com.atos.printbridge.domain.model.label.LabelTemplate
import br.com.atos.printbridge.domain.model.label.LabelZplRenderer
import br.com.atos.printbridge.domain.repository.LabelTemplateRepository
import javax.inject.Inject

class ObserveLabelTemplatesUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    operator fun invoke() = repository.observeAll()
}

class GetLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(id: String): LabelTemplate =
        repository.getById(id) ?: throw LabelTemplateNotFoundException(id)
}

class GetDefaultLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(): LabelTemplate =
        repository.getDefault() ?: throw NoDefaultLabelTemplateException()
}

/**
 * Semeia os modelos nativos apenas quando a tabela está vazia — nunca sobrescreve uma etiqueta que
 * o operador já editou, nunca duplica (ids determinísticos + REPLACE).
 */
class EnsureBuiltInLabelsUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(): Int {
        if (repository.count() > 0) return 0
        val seeds = BuiltInLabelTemplates.all()
        seeds.forEach { repository.upsert(it) }
        return seeds.size
    }
}

/**
 * Salva (cria/atualiza) um modelo. Toda a validação do layout fica aqui — a UI só mostra a
 * mensagem via `ErrorTranslator`.
 */
class SaveLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(template: LabelTemplate): LabelTemplate {
        val name = template.name.trim()
        if (name.isBlank()) throw InvalidLabelTemplateException("o nome não pode ficar em branco")
        if (name.length > LabelTemplate.MAX_NAME_LENGTH) {
            throw InvalidLabelTemplateException("o nome passa de ${LabelTemplate.MAX_NAME_LENGTH} caracteres")
        }
        if (!LabelTemplate.isValidWidth(template.widthMm) || !LabelTemplate.isValidHeight(template.heightMm)) {
            throw InvalidLabelTemplateException(
                "a largura e a altura devem ficar entre ${LabelTemplate.MIN_MM} e ${LabelTemplate.MAX_MM} mm",
            )
        }
        if (!LabelTemplate.isValidDpi(template.dpi)) {
            throw InvalidLabelTemplateException("resolução (dpi) não suportada: ${template.dpi}")
        }
        if (template.elements.size > MAX_ELEMENTS) {
            throw InvalidLabelTemplateException("a etiqueta passa de $MAX_ELEMENTS elementos")
        }
        template.elements.forEach { validateElement(it, template) }

        val now = System.currentTimeMillis()
        val saved = template.copy(
            name = name,
            createdAt = if (template.createdAt > 0) template.createdAt else now,
            updatedAt = now,
        )
        repository.upsert(saved)
        return saved
    }

    private fun validateElement(element: LabelElement, template: LabelTemplate) {
        val tolerance = 0.5f
        if (element.widthMm <= 0f || element.heightMm <= 0f) {
            throw InvalidLabelTemplateException("todo elemento precisa de largura e altura maiores que zero")
        }
        if (element.xMm < 0f || element.yMm < 0f) {
            throw InvalidLabelTemplateException("nenhum elemento pode começar fora da etiqueta (x/y negativos)")
        }
        if (element.xMm + element.widthMm > template.widthMm + tolerance ||
            element.yMm + element.heightMm > template.heightMm + tolerance
        ) {
            throw InvalidLabelTemplateException(
                "o elemento '${element.type.label}' ultrapassa os limites de ${template.widthMm}x${template.heightMm} mm",
            )
        }
        if (element.text.length > LabelTemplate.MAX_TEXT_LENGTH) {
            throw InvalidLabelTemplateException("um dos textos passa de ${LabelTemplate.MAX_TEXT_LENGTH} caracteres")
        }
        if (element.rotationDeg !in LabelElement.ROTATIONS) {
            throw InvalidLabelTemplateException("rotação inválida: ${element.rotationDeg}°")
        }
        if (element.type == br.com.atos.printbridge.domain.model.label.LabelElementType.CAMPO && element.field == null) {
            throw InvalidLabelTemplateException("todo elemento de campo precisa de um campo variável escolhido")
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 500
    }
}

class DeleteLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.getById(id) ?: throw LabelTemplateNotFoundException(id)
        repository.delete(id)
    }
}

class SetDefaultLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.getById(id) ?: throw LabelTemplateNotFoundException(id)
        repository.setDefault(id)
    }
}

class DuplicateLabelTemplateUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
) {
    suspend operator fun invoke(id: String): LabelTemplate {
        val original = repository.getById(id) ?: throw LabelTemplateNotFoundException(id)
        val now = System.currentTimeMillis()
        val copy = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${original.name} (cópia)".take(LabelTemplate.MAX_NAME_LENGTH),
            isBuiltIn = false,
            isDefault = false,
            elements = original.elements.mapIndexed { index, element ->
                element.copy(id = "${java.util.UUID.randomUUID()}-$index")
            },
            createdAt = now,
            updatedAt = now,
        )
        repository.upsert(copy)
        return copy
    }
}

/** Renderiza o ZPL sem imprimir — usado pela pré-visualização técnica e pelos testes do renderer. */
class RenderLabelZplUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
    private val renderer: LabelZplRenderer,
) {
    suspend operator fun invoke(templateId: String, data: LabelData = LabelData.sample()): String {
        val template = repository.getById(templateId) ?: throw LabelTemplateNotFoundException(templateId)
        return renderer.render(template, data)
    }

    operator fun invoke(template: LabelTemplate, data: LabelData = LabelData.sample()): String =
        renderer.render(template, data)
}

/**
 * Impressão de etiqueta (Fases 10 e 11).
 *
 * **Não** cria transporte, fila, worker nem histórico próprios: renderiza o ZPL e entrega ao
 * `EnqueuePrintJobUseCase` já existente, que persiste um `PrintJob` e aciona o
 * `PrintQueueWorker` → `PrintEngine` → `TcpPrinterTransport`. `printerId = null` significa
 * "impressora padrão" (mesma regra de todo o app).
 *
 * O job nasce `PENDING` e o app só chega a `SENT` quando o socket aceita os bytes — "SENT" **não**
 * é confirmação de impressão física (ver `PrintJobStatus`).
 */
class PrintLabelUseCase @Inject constructor(
    private val repository: LabelTemplateRepository,
    private val renderer: LabelZplRenderer,
    private val enqueuePrintJob: EnqueuePrintJobUseCase,
) {
    suspend operator fun invoke(
        templateId: String,
        data: LabelData = LabelData.sample(),
        printerId: String? = null,
        copies: Int = 1,
    ): PrintJob {
        val template = repository.getById(templateId) ?: throw LabelTemplateNotFoundException(templateId)
        return invoke(template, data, printerId, copies)
    }

    suspend operator fun invoke(
        template: LabelTemplate,
        data: LabelData = LabelData.sample(),
        printerId: String? = null,
        copies: Int = 1,
    ): PrintJob {
        val zpl = renderer.render(template, data)
        return enqueuePrintJob(
            printerId = printerId,
            type = PrintPayloadType.ZPL,
            payload = zpl,
            documentName = "Etiqueta: ${template.name}",
            copies = copies,
            externalDocumentId = template.id,
            sourceApp = JobSource.APP_LABELS,
        )
    }

    /** "Imprimir N etiquetas" do operador: N jobs independentes (ver [EnqueuePrintJobUseCase.enqueueQuantity]). */
    suspend fun printQuantity(
        template: LabelTemplate,
        data: LabelData,
        printerId: String?,
        quantity: Int,
    ): List<PrintJob> = enqueuePrintJob.enqueueQuantity(
        printerId = printerId,
        type = PrintPayloadType.ZPL,
        payload = renderer.render(template, data),
        documentName = "Etiqueta: ${template.name}",
        quantity = quantity,
        externalDocumentId = template.id,
        sourceApp = JobSource.APP_LABELS,
    )
}
