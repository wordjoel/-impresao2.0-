# Fase 1 — ATHOS Print Bridge Core (impressão direta coletor ↔ térmica)

Implementação do `PROMPT_MESTRE_ATHOS_PRINT_BRIDGE.md` **sobre o app existente** (A.print.Bridge,
`br.com.atos.printbridge`), não como projeto novo: fila Room, transporte TCP, worker e telas já
existiam e foram reaproveitados. Branch `feat/fase1-print-bridge-core`, empilhada sobre
`feat/p1.3-zpl-validator` (o schema Room é linear: P1.3 = v6, Fase 1 = v7).

Estado em 2026-09-21: **completo; critérios 1-6 validados no coletor real (Bluebird CF550) com impressora simulada. Pendente: saída física na TSC (nível C do `ROTEIRO-TESTE-FASE1.md`) e coletor sob MDM (critério 7).**

## O que existia × o que a Fase 1 entregou

| Módulo do prompt | Onde está | Situação |
|---|---|---|
| 1. Printer Profile Manager | `PrinterEntity` + `SavePrinterUseCase` + tela de impressora | Já existia; ganhou **DPI (203/300) e papel padrão (mm)** — Room v7 |
| 2. Socket Sender | `TcpPrinterTransport` | Já existia; backoff **linear → exponencial 2-4-8-16-32s, 5 tentativas** (`RetryPolicy`) |
| 3. Template Renderer | `domain/template/` | **Novo**: `{{campo}}`, recusa variável faltando e valor com caractere de comando; template SIF 100x72 |
| 4. Document Rasterizer | `domain/raster/` + `printing/raster/` | **Novo**: `PdfRenderer`/`BitmapFactory` → threshold ou Floyd–Steinberg → `^GFA` / TSPL `BITMAP` |
| 5. Offline Queue | fila Room + `PrintQueueWorker` | Já existia; ganhou **grupo de páginas** (`groupId/pageIndex/pageCount`) e posição na fila |
| 6. Confirmation/Telemetry | `PrintJobEventBus` + `PrintStatusNotifier` + `Logger` | **Novo**: eventos por job, callbacks AIDL, notificação ao operador |
| API local | `service/PrintBridgeService` + `api/` + `aidl/` | **Novo**: Bound Service AIDL, permissão `signature`, cliente de referência `PrintBridgeClient` |

## Critérios de aceite (seção 6 do prompt)

| # | Critério | Evidência automatizada | Validação física |
|---|---|---|---|
| 1 | Template estruturado sai pela impressora sem PDF/Acrobat/Print Framework | `PlaceholderTemplateRendererTest` (ZPL byte a byte), `PrintBridgeCoreTest`, E2E `templateJobReachesThePrinterByteExactAndCallerIsNotified` (bind real + socket no aparelho) | **Pendente** — TSC ML241P real |
| 2 | PDF simples sai rasterizado | `RasterPipelineTest`, `AndroidDocumentRasterizerPdfTest` (PdfRenderer real, androidTest) | **Pendente** — nitidez/posição |
| 3 | PDF multipágina imprime todas em sequência | `PrintBridgeCoreTest` (ordem no socket), E2E `multiPagePdfPrintsEveryPageInOrderAsRasterGraphics` | **Pendente** |
| 4 | Impressora desligada → job fica na fila e reenvia sozinho quando volta | `OfflinePrinterRecoveryTest` (socket TCP real: recusado 2x → servidor sobe → entregue na 3ª) | **Pendente** — desligar a TSC de verdade |
| 5 | Após 5 falhas → `FAILED` visível | `ProcessPrintQueueUseCaseTest` (2+4+8+16s, FAILED), `PrintStatusMessagesTest` | **Pendente** |
| 6 | Chamador recebe sucesso/erro | `PrintBridgeContractTest` (eventos e códigos), E2E (callback `job-completed`) | — |
| 7 | Funciona em Android puro e sob MDM, sem permissão de sistema | Só permissões normais + `POST_NOTIFICATIONS`; serviço protegido por `signature` própria | **Pendente** — coletor com `EnterpriseFOTA` |

Como rodar:

```
./gradlew :app:testDebugUnitTest              # 259 testes JVM/Robolectric
./gradlew :app:connectedDebugAndroidTest      # PDF real + E2E da API (coletor/emulador com licença ativa)
```

## Decisões tomadas durante a implementação

1. **Evoluir o app existente em vez de começar do zero** (aprovado pelo usuário): metade do escopo já estava pronta e homologada em laboratório.
2. **TSPL usa `BITMAP` inline, não `PUTBMP`**: `PUTBMP` exige `DOWNLOAD` prévio do .BMP para a flash da impressora a cada página. Caminho previsto no ponto 8.4 do prompt.
3. **Payload TSPL guardado como ISO-8859-1** (`PrintPayloadType.TSPL.wireCharset`): o `BITMAP` é binário e UTF-8 corromperia bytes > 0x7F. Teste ponta a ponta garante os bytes idênticos no socket.
4. **Página rasterizada = tamanho exato da etiqueta** do perfil, conteúdo encaixado sem distorção (como o "ajustar à página" do fluxo antigo), topo alinhado.
5. **Contrato AIDL em `Bundle`**, não Parcelable próprio: cliente antigo continua funcionando quando o contrato ganhar chaves. Erros viajam como código estável, nunca exceção/stack trace pelo Binder.
6. **Callbacks só para o app (uid) que enviou o job.** O vínculo job→app fica em memória: se o processo do Print Bridge reiniciar, o cliente ainda consulta `getJobStatus` (estado durável está no Room).
7. **Valores de template com caractere de comando são recusados**, não escapados — escapar dependeria de cada template usar `^FH`.
8. **Corrigido de passagem:** `AppSettings.maxAttempts` aparecia na tela mas nunca era aplicado aos jobs.
9. **Extensão ao contrato:** `JobStatus.Cancelled` (a fila já permitia cancelar pela tela).

## Pontos em aberto — validar com a TSC real

1. **DPI real** das impressoras em campo (203 ou 300) — cadastrar no perfil.
2. **Polaridade do `BITMAP` TSPL** — implementado com bit 0 = ponto queimado (padrão TSC). Se sair em negativo, inverter em `TsplGraphicEncoder`.
3. **Acentuação** — o template SIF foi transcrito exatamente do prompt, sem `^CI28`. Se "Peso Líquido" sair com o "í" errado, adicionar `^CI28` (UTF-8) ao template, se o firmware/emulação ZPL da TSC suportar.
4. **Orientação 180°** — verificar se é o rolo ou se o template precisa de `^POI`.
5. **Conteúdo dos 4 códigos de barras** do SIF (ponto 8.3 do prompt).
6. **PDF multipágina**: implementado como impressão automática em sequência (default do prompt, ponto 8.2).
7. **Tamanho do `^GFA`**: hex sem compressão (~115 KB por página 100x72 a 203dpi). Se a latência pesar em Wi-Fi fraco, adotar compressão ZPL/Z64.

## Fora de escopo (preparado, não implementado)

- `PrintService` do Android (Fase 2): **feito em 2026-09-23**, ver `FASE2-PRINTSERVICE.md`.
- Templates de varejo além do SIF; integrações externas; dashboards.
- Publicar `:printbridge-api` num repositório Maven interno (hoje os apps clientes a consomem como módulo/AAR).

## Validação no coletor real (2026-09-21, Bluebird CF550, Android 11)

Rodado no coletor de referência, sem TSC física (nenhuma impressora na rede):

- Instrumentados (PdfRenderer real + E2E AIDL com bind real): **5/5**.
- PB Cliente Teste (app separado, mesma assinatura) → API AIDL → fila → TCP: SIF, PDF 1 e 3
  páginas, imagem, ZPL bruto, erro `MISSING_VARIABLES`, status — todos OK, callbacks corretos.
  PDF e imagem renderizados de volta a partir do `^GFA` recebido: texto nítido, dithering correto.
- Impressora desligada: recusas com backoff 2/4/8/16s medido no aparelho; ligada → entregue
  sozinho na 5ª tentativa (30,5 s). Desligada até o fim → `job-failed` + notificação persistente.

Infraestrutura de homologação em `tools/homologacao/` (instalação, licença de laboratório por ADB,
teste de fumaça com veredito, impressora simulada no PC com render PNG) e pacote gerado por
`empacotar-entrega.ps1`. Roteiro para o homologador: `ROTEIRO-TESTE-FASE1.md`.

Contrato público extraído para o módulo `:printbridge-api` (AIDL + tipos + `PrintBridgeClient`),
consumido pelo app e pelo `:sample-client` — é a dependência que os apps ATHOS reais vão usar.
