package br.com.atos.printbridge.diagnostics

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import br.com.atos.printbridge.api.DocumentType
import br.com.atos.printbridge.api.PrintBridgeClient
import br.com.atos.printbridge.api.PrintBridgeContract as C
import br.com.atos.printbridge.api.PrintBridgeRemoteException
import br.com.atos.printbridge.api.PrintJobRequest
import br.com.atos.printbridge.api.PrinterProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ferramenta OCULTA de teste da API local (ex-app "PB Cliente Teste"), dentro do próprio A.print.Bridge.
 * Fala com o app pela MESMA API AIDL dos apps ATHOS ([PrintBridgeClient]: bind, printJob, callbacks),
 * então cada teste passa pelo caminho real — não é atalho interno. Só não prova a permissão
 * `signature` entre APKs diferentes (mesmo uid não é checado); para isso existe o módulo
 * `:sample-client`, cliente de exemplo para os integradores.
 *
 * Sem ícone no launcher. Abre por: 7 toques na versão em Configurações, ou por ADB
 * (exported + permissão DUMP, que só o shell/sistema têm — nenhum app de terceiro abre):
 *   adb shell am start -n br.com.atos.printbridge.debug/br.com.atos.printbridge.diagnostics.ApiTestToolActivity
 *     --es ip 127.0.0.1 --es protocolo ZPL --es dpi 203 --es papel 100x72
 *     --es acao registrar,sif,pdf1,pdf3,imagem,raw,erro,status --es execucao <id único>
 * O log sai no logcat com a tag PBClienteTeste (a mesma do cliente antigo; os scripts leem por ela).
 */
// Ferramenta interna de diagnóstico, só em português: textos montados na hora, sem string resources.
@SuppressLint("SetTextI18n")
class ApiTestToolActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridge: PrintBridgeClient? = null
    private var lastJobId: String? = null

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var protocol: Spinner
    private lateinit var dpi: Spinner
    private lateinit var paperField: EditText

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        connect()
    }

    override fun onDestroy() {
        bridge?.close()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- conexão

    private fun connect() = scope.launch {
        status.text = "Conectando à API do A.print.Bridge…"
        try {
            val client = PrintBridgeClient.connect(this@ApiTestToolActivity, packageName)
            bridge = client
            client.setListener { event -> runOnUiThread { logEvent(event) } }
            status.text = "Conectado (API v${client.apiVersion})"
            append("Conectado. Impressora: ${prefs.getString(KEY_PRINTER, null) ?: "padrão do Print Bridge"}")
            runScript(intent)
        } catch (e: Exception) {
            status.text = "Sem conexão: ${e.message}"
            append("ERRO ao conectar: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (bridge != null) runScript(intent)
    }

    // ---------------------------------------------------------------- modo roteiro (ADB)

    /**
     * Cada roteiro roda UMA vez. O Android pode reentregar a intent original (tela recriada, intent
     * antiga do registro da activity) e `removeExtra` só limpa a cópia em memória — o cliente antigo
     * chegou a repetir um roteiro e imprimir etiquetas a mais. O id de execução não repete; sem id
     * (uso manual por ADB) vale o conteúdo da intent.
     */
    private fun runScript(intent: Intent?) {
        val extras = intent?.extras ?: return
        val actions = extras.getString("acao")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: return
        val execution = extras.getString("execucao") ?: extras.keySet().sorted().joinToString("|") { "$it=${extras.get(it)}" }
        if (!claimExecution(execution)) {
            Log.i(LOG_TAG, "roteiro já executado, ignorado: $execution")
            return
        }
        extras.getString("ip")?.let { ipField.setText(it) }
        extras.getString("porta")?.let { portField.setText(it) }
        extras.getString("protocolo")?.let { p -> protocol.setSelection(if (p.equals("TSPL", true)) 1 else 0) }
        extras.getString("dpi")?.let { d -> dpi.setSelection(if (d == "300") 1 else 0) }
        extras.getString("papel")?.let { paperField.setText(it) }
        scope.launch {
            append("=== ROTEIRO: ${actions.joinToString()} ===")
            for (action in actions) {
                if (action == "fechar") { append("=== FIM DO ROTEIRO ==="); finish(); return@launch }
                val job = when (action) {
                    "registrar" -> registerPrinter()
                    "listar" -> listPrinters()
                    "sif" -> printSif()
                    "pdf1" -> printPdf(1)
                    "pdf3" -> printPdf(3)
                    "imagem" -> printImage()
                    "raw" -> printRaw()
                    "erro" -> printMissingVariable()
                    "status" -> lastStatus()
                    else -> { append("✗ ação desconhecida: $action"); null }
                }
                job?.join()
                delay(SCRIPT_PAUSE_MS) // dá tempo da fila enviar e dos callbacks chegarem
            }
            append("=== FIM DO ROTEIRO ===")
        }
    }

    // ---------------------------------------------------------------- ações

    private fun registerPrinter() = call("Cadastrar impressora") { b ->
        val ip = ipField.text.toString().trim()
        val profile = PrinterProfile(
            id = prefs.getString(KEY_PRINTER, null),
            storeId = "loja-teste",
            name = "Teste API $ip",
            ipAddress = ip,
            port = portField.text.toString().toIntOrNull() ?: 9100,
            protocol = protocol.selectedItem.toString(),
            dpi = dpi.selectedItem.toString().toInt(),
            defaultPaperSize = paperField.text.toString().trim(),
            isDefault = false,
        )
        val id = b.registerPrinterProfile(profile)
        prefs.edit().putString(KEY_PRINTER, id).apply()
        "Impressora cadastrada/atualizada: $id (${profile.protocol} ${profile.dpi}dpi ${profile.defaultPaperSize})"
    }

    private fun listPrinters() = call("Listar impressoras") { b ->
        b.listPrinterProfiles().joinToString("\n") {
            "• ${it.name} ${it.ipAddress}:${it.port} ${it.protocol} ${it.dpi}dpi ${it.defaultPaperSize}${if (it.isDefault) " [padrão]" else ""} id=${it.id}"
        }.ifEmpty { "Nenhuma impressora cadastrada." }
    }

    private fun printSif() = submit("Etiqueta SIF (template)") {
        PrintJobRequest(
            documentType = DocumentType.TEMPLATE,
            templateId = "sif-pesagem-100x72",
            variables = mapOf(
                "loteSequencia" to "96644",
                "pesoLiquido" to "10,020 Kg",
                "pesoBruto" to "10,390 Kg",
                "codigoBarra1" to "7891234567895",
                "codigoBarra2" to "17123456",
                "codigoBarra3" to "96644",
                "codigoBarra4" to "1002",
                "qrCodePayload" to "ATHOS-LOTE-96644",
            ),
            documentName = "SIF lote 96644",
            externalDocumentId = "PESAGEM-96644",
        )
    }

    private fun printMissingVariable() = submit("Template com variável faltando (deve recusar)") {
        PrintJobRequest(DocumentType.TEMPLATE, templateId = "sif-pesagem-100x72", variables = mapOf("loteSequencia" to "1"))
    }

    private fun printPdf(pages: Int) = submit("PDF de $pages página(s)") {
        PrintJobRequest(DocumentType.PDF, documentBytes = samplePdf(pages), documentName = "PDF teste $pages pág.")
    }

    private fun printImage() = submit("Imagem (dithering)") {
        PrintJobRequest(DocumentType.IMAGE, documentBytes = sampleImage(), documentName = "Imagem teste")
    }

    private fun printRaw() = submit("ZPL/TSPL bruto") {
        val tspl = protocol.selectedItem.toString() == "TSPL"
        val raw = if (tspl) {
            "SIZE 100 mm,72 mm\r\nCLS\r\nTEXT 40,40,\"4\",0,1,1,\"PRINT BRIDGE RAW TSPL\"\r\nPRINT 1\r\n"
        } else {
            "^XA^FO40,40^A0N,50,50^FDPRINT BRIDGE RAW ZPL^FS^FO40,120^BCN,80,Y,N,N^FD123456789^FS^XZ"
        }
        PrintJobRequest(DocumentType.RAW_ZPL_TSPL, rawPayload = raw, documentName = "RAW teste")
    }

    private fun lastStatus() = call("Status do último job") { b ->
        val id = lastJobId ?: return@call "Nenhum job enviado nesta sessão."
        "$id → ${b.getJobStatus(id)}"
    }

    private fun submit(label: String, build: () -> PrintJobRequest) = call(label) { b ->
        val request = build().copy(printerProfileId = prefs.getString(KEY_PRINTER, null))
        val started = System.currentTimeMillis()
        val id = b.printJob(request)
        lastJobId = id
        "OK jobId=$id (aceito e persistido em ${System.currentTimeMillis() - started} ms)"
    }

    private fun call(label: String, block: suspend (PrintBridgeClient) -> String): Job {
        val b = bridge ?: run { append("✗ $label: não conectado"); return Job().apply { complete() } }
        append("→ $label")
        return scope.launch {
            try {
                append("  ${block(b)}")
            } catch (e: PrintBridgeRemoteException) {
                val missing = if (e.missingVariables.isNotEmpty()) " faltando=${e.missingVariables}" else ""
                append("  ✗ ${e.message}$missing")
            } catch (e: Exception) {
                append("  ✗ ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun logEvent(event: Bundle) {
        val type = event.getString(C.EVENT_TYPE)
        val detail = when (type) {
            C.EVENT_JOB_RETRYING -> "tentativa ${event.getInt(C.EVENT_ATTEMPT)}/${event.getInt(C.EVENT_MAX_ATTEMPTS)} " +
                "próxima em ${event.getLong(C.EVENT_NEXT_RETRY_MS) / 1000}s — ${event.getString(C.EVENT_ERROR)}"
            C.EVENT_JOB_FAILED -> "FALHOU após ${event.getInt(C.EVENT_ATTEMPT)} tentativas — ${event.getString(C.EVENT_ERROR)}"
            C.EVENT_JOB_COMPLETED -> "entregue à impressora em ${event.getLong(C.EVENT_DURATION_MS)} ms"
            C.EVENT_PAGE_PRINTED -> "página ${event.getInt(C.EVENT_PAGE)}/${event.getInt(C.EVENT_PAGE_COUNT)}"
            C.EVENT_DOCUMENT_FINISHED -> "${event.getInt(C.EVENT_PAGES_OK)}/${event.getInt(C.EVENT_PAGE_COUNT)} páginas ok"
            else -> ""
        }
        append("  ⇐ $type $detail  [${event.getString(C.EVENT_JOB_ID)?.take(8)}]")
    }

    private fun append(line: String) {
        Log.i(LOG_TAG, line)
        log.append("${time.format(Date())} $line\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- documentos de exemplo

    /** PDF no tamanho 100x72mm (283x204pt), com texto, caixa e número da página. */
    private fun samplePdf(pages: Int): ByteArray {
        val pdf = PdfDocument()
        val text = Paint().apply { color = Color.BLACK; textSize = 18f; isAntiAlias = true }
        val big = Paint(text).apply { textSize = 30f; isFakeBoldText = true }
        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        repeat(pages) { i ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(283, 204, i + 1).create())
            with(page.canvas) {
                drawRect(6f, 6f, 277f, 198f, stroke)
                drawText("ATHOS Print Bridge", 16f, 44f, big)
                drawText("Teste Fase 1 — PDF rasterizado", 16f, 74f, text)
                drawText("Página ${i + 1} de $pages", 16f, 104f, text)
                drawText(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date()), 16f, 134f, text)
                for (x in 0 until 40) if (x % 3 != 0) drawRect(16f + x * 6, 150f, 19f + x * 6, 186f, Paint())
            }
            pdf.finishPage(page)
        }
        return ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()
    }

    /** PNG com gradiente (exercita o Floyd–Steinberg) e texto. */
    private fun sampleImage(): ByteArray {
        val bmp = Bitmap.createBitmap(800, 576, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val gradient = Paint().apply { shader = LinearGradient(0f, 0f, 800f, 0f, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 300f, 800f, 576f, gradient)
        canvas.drawText("IMAGEM + DITHERING", 40f, 120f, Paint().apply { color = Color.BLACK; textSize = 64f; isFakeBoldText = true })
        canvas.drawCircle(680f, 150f, 90f, Paint().apply { color = Color.DKGRAY })
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "Ferramenta de teste da API (oculta)"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(TextView(this).apply { text = "Impressora de teste (vazio = usa a padrão do Print Bridge):" })
        ipField = EditText(this).apply { hint = "IP da impressora (ex.: 192.168.70.106)"; inputType = InputType.TYPE_CLASS_PHONE }
        portField = EditText(this).apply { setText("9100"); inputType = InputType.TYPE_CLASS_NUMBER }
        protocol = Spinner(this).apply { adapter = ArrayAdapter(this@ApiTestToolActivity, android.R.layout.simple_spinner_dropdown_item, listOf("ZPL", "TSPL")) }
        dpi = Spinner(this).apply { adapter = ArrayAdapter(this@ApiTestToolActivity, android.R.layout.simple_spinner_dropdown_item, listOf("203", "300")) }
        paperField = EditText(this).apply { setText("100x72"); hint = "papel LxA mm" }
        root.addView(row(ipField, portField))
        root.addView(row(protocol, dpi, paperField))
        root.addView(row(button("Cadastrar impressora") { registerPrinter() }, button("Listar") { listPrinters() }))

        root.addView(row(button("1 Etiqueta SIF") { printSif() }, button("2 PDF 1 pág") { printPdf(1) }))
        root.addView(row(button("3 PDF 3 págs") { printPdf(3) }, button("4 Imagem") { printImage() }))
        root.addView(row(button("5 ZPL/TSPL bruto") { printRaw() }, button("6 Erro: var faltando") { printMissingVariable() }))
        root.addView(row(button("Status último job") { lastStatus() }, button("Limpar log") { log.text = "" }))

        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(log) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        const val LOG_TAG = "PBClienteTeste"
        private const val SCRIPT_PAUSE_MS = 3_000L
        private const val KEY_PRINTER = "printerId"
        private const val PREFS = "api-test-tool"

        private val executed = HashSet<String>()

        /** true = primeira vez deste roteiro neste processo. */
        @Synchronized
        internal fun claimExecution(id: String): Boolean = executed.add(id)
    }
}
