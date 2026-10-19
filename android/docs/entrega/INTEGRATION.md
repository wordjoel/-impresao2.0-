# INTEGRATION.md — Como outro app Android solicita impressão ao Atos Print Bridge

Existem três caminhos:

- **Caminho 0 — API local (AIDL), Fase 1 — recomendado para apps ATHOS.** Template com variáveis, PDF, imagem ou ZPL/TSPL bruto; devolve `jobId`, consulta de status e callbacks de sucesso/falha/retry. Exige a mesma assinatura ATHOS.
- **Caminho 1 — Ação customizada por Intent.** Só ZPL bruto, retorno imediato do `jobId`. Continua suportado.
- **Caminho 2 — `ACTION_SEND`.** Integração rápida com qualquer app que saiba "Compartilhar".

## Caminho 0 — API local `IPrintBridgeService` (Fase 1)

Elimina o fluxo antigo "PDF → Acrobat → Print Framework → TSC Mobile Utility": o app chamador entrega os dados e o Print Bridge renderiza, enfileira (persistido em Room antes de qualquer envio), envia por socket TCP 9100 com retry exponencial (2, 4, 8, 16, 32s; 5 tentativas) e **avisa o resultado**.

**Bind:** action `br.com.atos.printbridge.action.BIND_PRINT_BRIDGE`, pacote `br.com.atos.printbridge` (build debug: `br.com.atos.printbridge.debug`).
**Permissão:** `br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB` (`signature`).
**Contrato:** `app/src/main/aidl/br/com/atos/printbridge/api/*.aidl` + pacote `br.com.atos.printbridge.api` (autossuficiente: copie os dois para o app cliente, ou consuma como biblioteca quando for extraído).

Manifesto do app cliente:

```xml
<uses-permission android:name="br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB" />
<queries>
    <package android:name="br.com.atos.printbridge" />
</queries>
```

### Tipos de documento

| `documentType` | Campos obrigatórios | O que acontece |
|---|---|---|
| `TEMPLATE` | `templateId`, `variables` | `{{campo}}` substituído; variável faltando → erro `MISSING_VARIABLES` (nada é impresso). Valores com `^`/`~` (ZPL) ou `"` (TSPL) são recusados (`INVALID_VARIABLE_VALUE`). Template embarcado: `sif-pesagem-100x72`. |
| `PDF` | `documentBytes` ou `documentFd` | Cada página rasterizada no DPI e papel do perfil da impressora, 1 bit (threshold), `^GFA` (ZPL) ou `BITMAP` (TSPL). N páginas = N jobs em ordem; o id devolvido é o do documento. Limites: 50 páginas, 20 MB. |
| `IMAGE` | `documentBytes` ou `documentFd` | Idem, com dithering Floyd–Steinberg por padrão (`ditherMode` muda). |
| `RAW_ZPL_TSPL` | `rawPayload` | Enviado como veio, na linguagem configurada na impressora. |

`printerProfileId` nulo = impressora padrão. Documentos acima de ~500 KB devem ir por `documentFd` (limite de transação do Binder).

### Exemplo Kotlin (com o cliente de referência `PrintBridgeClient`)

```kotlin
val bridge = PrintBridgeClient.connect(context)          // suspende até o bind
bridge.setListener { event ->                            // só eventos dos SEUS jobs
    when (event.getString(PrintBridgeContract.EVENT_TYPE)) {
        PrintBridgeContract.EVENT_JOB_COMPLETED -> { /* bytes entregues à impressora */ }
        PrintBridgeContract.EVENT_JOB_FAILED -> { /* event.getString(EVENT_ERROR) após 5 tentativas */ }
        PrintBridgeContract.EVENT_JOB_RETRYING -> { /* tentativa n de 5, próxima em EVENT_NEXT_RETRY_MS */ }
        PrintBridgeContract.EVENT_PAGE_PRINTED -> { /* página EVENT_PAGE de EVENT_PAGE_COUNT */ }
        PrintBridgeContract.EVENT_DOCUMENT_FINISHED -> { /* EVENT_PAGES_OK de EVENT_PAGE_COUNT */ }
    }
}
try {
    val jobId = bridge.printJob(
        PrintJobRequest(
            documentType = DocumentType.TEMPLATE,
            templateId = "sif-pesagem-100x72",
            variables = mapOf("loteSequencia" to "96644", "pesoLiquido" to "10,020 Kg", /* ... */),
            externalDocumentId = "PESAGEM-96644",
        ),
    )
    val status = bridge.getJobStatus(jobId)               // Queued/Sending/Completed/Failed/PartiallyCompleted/Cancelled
} catch (e: PrintBridgeRemoteException) {
    // e.code: PrintBridgeContract.ERROR_* (decida pelo código, nunca pelo texto)
}
```

### Códigos de erro

`INVALID_REQUEST`, `UNKNOWN_TEMPLATE`, `MISSING_VARIABLES` (+ lista `missingVariables`), `INVALID_VARIABLE_VALUE`, `LANGUAGE_MISMATCH`, `DOCUMENT_UNREADABLE`, `PAYLOAD_TOO_LARGE`, `NO_PRINTER`, `INVALID_PRINTER_PROFILE`, `LICENSE_RESTRICTED`, `JOB_NOT_FOUND`, `INTERNAL`.

### Semântica de "Completed"

`Completed`/`job-completed` = todos os bytes foram aceitos pelo socket da impressora. A TSC não devolve confirmação de impressão física nesta fase — mesma semântica do status `SENT` da fila.

### Perfis de impressora

`registerPrinterProfile(PrinterProfile(id=null, storeId, name, ipAddress, port=9100, protocol="ZPL"|"TSPL", dpi=203|300, defaultPaperSize="100x72", isDefault))` cria (ou atualiza, com `id`) e devolve o id. `listPrinterProfiles(storeId)` — vazio lista todas.

## Caminho 1 — Ação customizada por Intent (ZPL bruto)

**Ação:** `br.com.atos.printbridge.action.SUBMIT_PRINT_JOB`
**Permissão exigida:** `br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB` (nível `signature` — seu app precisa declarar `<uses-permission>` para essa permissão e ser assinado com a mesma chave do Atos Print Bridge).

### Extras

| Extra | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `br.com.atos.printbridge.extra.ZPL_CONTENT` | String | Sim | Conteúdo ZPL bruto |
| `br.com.atos.printbridge.extra.DOCUMENT_NAME` | String | Não | Nome amigável do documento |
| `br.com.atos.printbridge.extra.COPIES` | Int | Não (default 1) | Quantidade de cópias |
| `br.com.atos.printbridge.extra.PRINTER_ID` | String | Não | Se ausente, usa a impressora padrão |
| `br.com.atos.printbridge.extra.EXTERNAL_DOCUMENT_ID` | String | Não | Identificador do documento no seu sistema |

### Retorno (via `startActivityForResult`/`ActivityResultContracts`)

- `RESULT_OK` + extra `br.com.atos.printbridge.extra.RESULT_JOB_ID` (String) — job criado com sucesso.
- `RESULT_CANCELED` + extra `br.com.atos.printbridge.extra.RESULT_ERROR` (String) — motivo da rejeição (payload vazio, impressora não encontrada, sem impressora padrão, payload grande demais).

### Exemplo Kotlin

```kotlin
// build.gradle do app cliente:
// <uses-permission android:name="br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB" />

class MeuAppActivity : ComponentActivity() {

    private val printLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val jobId = result.data?.getStringExtra("br.com.atos.printbridge.extra.RESULT_JOB_ID")
            Log.i("MeuApp", "Impressão enfileirada: $jobId")
        } else {
            val error = result.data?.getStringExtra("br.com.atos.printbridge.extra.RESULT_ERROR")
            Log.w("MeuApp", "Falha ao enfileirar impressão: $error")
        }
    }

    fun enviarParaImpressao(zpl: String) {
        val intent = Intent("br.com.atos.printbridge.action.SUBMIT_PRINT_JOB").apply {
            setPackage("br.com.atos.printbridge") // use "br.com.atos.printbridge.debug" para build debug
            putExtra("br.com.atos.printbridge.extra.ZPL_CONTENT", zpl)
            putExtra("br.com.atos.printbridge.extra.DOCUMENT_NAME", "Etiqueta pedido #1234")
            putExtra("br.com.atos.printbridge.extra.COPIES", 2)
        }
        printLauncher.launch(intent)
    }
}
```

> Os nomes dos extras estão centralizados em `br.com.atos.printbridge.integration.PrintBridgeIntentContract` no código-fonte do Print Bridge — use-o como referência canônica caso o app cliente também seja Kotlin e tenha acesso ao módulo.

## Caminho 2 — `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (compartilhar texto ou arquivo)

Qualquer app que ofereça "Compartilhar" pode enviar para o Atos Print Bridge (ele aparecerá na folha de compartilhamento do Android). Aceito neste caminho:

- **Texto** (`EXTRA_TEXT`, MIME `text/plain`) → tratado como ZPL.
- **Arquivo** (`EXTRA_STREAM`, qualquer MIME) → lido com leitura limitada a 512 KB:
  - `text/*`, `application/octet-stream`, `*zpl*` ou conteúdo que abre com cabeçalho ZPL (`^XA`) ou é ASCII puro → enfileirado como ZPL;
  - `image/*`, `video/*`, `audio/*`, `application/pdf` → **rejeitado com mensagem clara** (impressora térmica TSC de etiquetas não rasteriza página — o mapeamento seria enganoso; ver nota abaixo).
- **Múltiplos arquivos** (`EXTRA_STREAM` em `ACTION_SEND_MULTIPLE`) → um job por arquivo, no limite de 20.

O trabalho vai para a impressora **padrão** — não há como escolher impressora ou definir cópias por esse caminho.

```kotlin
// texto
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, zplContent)
}
startActivity(Intent.createChooser(intent, "Enviar etiqueta"))

// arquivo (qualquer provedor de arquivos/SAF)
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_STREAM, uriDoArquivo)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivity(Intent.createChooser(intent, "Enviar etiqueta"))
```

### Por que não `PrintService`/Print Framework (limitação técnica documentada)

O Android Print Framework **rasteriza** o documento para o serviço de impressão, entregando páginas PNG/PDF — não há mapeamento para ZPL de etiqueta térmica, e não existe "impressora Android" configurável no Print Bridge (a impressora TSC é acessada por TCP/IP puro na rede local). Adotar PrintService exigiria rasterizar a etiqueta e re-converter para ZPL (perda de qualidade, lógica duplicada e dependência de classe de impressão do Android). `ACTION_SEND` preserva o conteúdo original e deixa o Print Bridge decidir — por isso é a via correta para este produto.

**Atualização Fase 1 (2026-09-21):** PDF e imagem agora são impressos pela API local (Caminho 0) via Document Rasterizer próprio (`PdfRenderer` nativo → 1 bit → `^GFA`/`BITMAP`), sem Print Framework. Tornar o Print Bridge um `PrintService` visível a apps de terceiros é a **Fase 2**: o rasterizador já existe e é o mesmo componente que o `PrintService` vai chamar, então a Fase 2 não refaz o motor.

## Limites e validações

- Payload maior que **512 KB** é rejeitado sem criar job (`PayloadTooLargeException`).
- Payload vazio/em branco é rejeitado (`EmptyPayloadException`).
- Se nenhuma impressora padrão estiver configurada e nenhum `PRINTER_ID` for informado, a requisição é rejeitada (`NoDefaultPrinterException`) — configure ao menos uma impressora padrão no Atos Print Bridge antes de integrar.
