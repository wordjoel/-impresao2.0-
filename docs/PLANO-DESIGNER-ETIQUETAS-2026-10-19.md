# Plano do designer de etiquetas — 2026-10-19

Plano para levar o `designer/` (athos-label) de "funciona contra impressora simulada" a "pronto para loja",
um passo por vez. Escrito a partir do código como está em `c1a8a54` + alterações locais, não de memória.

## 1. Ponto de partida (verificado em 2026-10-19)

| Item | Estado | Evidência |
|---|---|---|
| Testes | **107 passando**, 0 falhas (engine 85, exporters 13, print-server 9); `npm run typecheck` sem erros | `npm test`, `npm run typecheck` em `designer/` |
| Impressão física | **NÃO TESTADA** — ZPL/TSPL/EPL só contra impressora simulada (servidor TCP) | aviso no `README.md`, seção "Designer de etiquetas" |

O que já existe, por módulo:

| Módulo | Já implementado | Onde |
|---|---|---|
| Área de desenho | coordenadas em mm, snap, Smart Guides, alinhar/distribuir, rotação, travar objeto, seleção | `engine/src/geometry/*`, `apps/web/src/components/LabelCanvas.tsx` |
| Objetos | texto (quebra, redução automática), código 1D (Code128, EAN-13, UPC-A, Code39), QR, DataMatrix, imagem (monocromática), caixa | `engine/src/model/objects.ts`, `engine/src/barcode/*` |
| Dados | CSV (RFC 4180), JSON, API REST, dados fictícios, mapeamento de colunas, saneamento, transformações, expressões sem `eval` (`IF`, `SEQ`, `DATEADD`…), validação antes de imprimir | `engine/src/data/*`, `engine/src/expr/expression.ts` |
| Exportação | ZPL (`^CI28`, escape `^FH`), TSPL, EPL2, SVG, PDF; template compilado com cache | `exporters/src/*` |
| Templates e marca | kit de marca, variantes com herança, versões, A/B | `engine/src/model/brand.ts`, `engine/src/templates/*` |
| Impressão | servidor local com token, TCP 9100, lotes com checkpoint e retomada, roteamento por regra, status da impressora, **auditoria SHA-256 por etiqueta** | `apps/print-server/*`, `engine/src/print/orchestrator.ts` |

## 2. Regra sobre "engenharia reversa" de concorrentes

Referência de mercado (BarTender, NiceLabel) é estudada **só de fora para dentro**:

- **Permitido:** manuais públicos das linguagens de impressora (ZPL/Zebra, TSPL/TSC, EPL), comportamento
  observável dos programas, comparação de etiquetas impressas, nossos próprios arquivos e design.
- **Proibido:** descompilar, abrir ou converter arquivos proprietários (`.btw`, `.nlbl`), copiar telas,
  ícones, nomes ou modelos. As licenças desses produtos proíbem engenharia reversa, e um produto
  derivado disso fica juridicamente frágil para venda a clientes como Carrefour, Assaí e Roldão.

## 3. Correções ao plano de referência recebido (conversa com outra IA, 2026-10-19)

O plano de referência descrevia uma arquitetura que o `designer/` já tem quase toda. Alguns pontos
técnicos dele estavam errados e **não devem** ser copiados:

| O plano dizia | Correto |
|---|---|
| Escapar `^` como `^^` em ZPL | ZPL não tem `^^`; usar `^FH` + hexadecimal (o `zpl.ts` já faz) |
| `^FO50,50^A0N,30,30^{text}^FS` | falta `^FD` — o comando não imprime o texto |
| `^CW1,40` = "largura da fonte" | `^CW` associa uma letra a uma fonte; não define largura |
| `^CF` "embute fontes vetoriais" | `^CF` só escolhe a fonte padrão |
| `.btw` é XML para estudar | formato proprietário; abrir viola a licença (seção 2) |
| `mmToPx()` usando `this.width` | bug: converte a largura, não a posição |
| "1.000 etiquetas em 2 s" do BarTender | afirmação sem fonte; não usar como meta |

## 4. Passos

Um passo por vez, cada um com testes e critério de aceite antes do seguinte. Nada de mudança
"já que estou aqui". Ao pedir ao Claude, citar o número do passo e este arquivo.

### Passo 1 — Validação física na TSC MB241 (bloqueia todos os outros)

- **Por quê:** tudo até aqui foi validado contra impressora simulada. Sem isto, o resto é teoria.
- **Fazer:** uma etiqueta de teste 60×40 mm com texto acentuado (`AÇÚCAR ÁGUA`), texto girado 90°,
  EAN-13 válido, Code128, QR com URL, DataMatrix, imagem e caixa. Imprimir pela aba **Imprimir**
  (TSPL, que é a linguagem da MB241) e também pelo app IMPRESSÃO A, se o caminho existir.
- **Aceite:**
  - posição de cada objeto dentro de ±0,5 mm do layout (medir com régua; registrar a medida);
  - acentos corretos; texto girado no lugar certo;
  - EAN-13, Code128, QR e DataMatrix lidos pelo scanner do CF550 com o valor exato;
  - foto da etiqueta + medidas + versão do designer registradas em `docs/evidencias/`.
- **Se falhar:** registrar a diferença medida, corrigir só o exportador afetado, com teste que reproduz.

### Passo 2 — Simbologias de varejo e logística — EAN-8, ITF-14, GS1-128 FEITOS (2026-10-19, sem commit); PDF417 pendente

Feito no software (falta só a leitura física, que entra na etiqueta do passo 1):

| Simbologia | Entrada aceita | Como vai para a impressora | Por quê |
|---|---|---|---|
| EAN-8 | 7 dígitos (calcula) ou 8 (confere) | nativo: ZPL `^B8`, TSPL `"EAN8"`, EPL `E80`, 7 dígitos | comando documentado e igual ao do EAN-13, que já é nativo |
| ITF-14 | 13 dígitos (calcula) ou 14 (confere); espaços ignorados | **imagem** das barras + texto legível com a fonte da impressora | comando nativo muda entre ZPL/TSPL/EPL (razão de barras, verificador) e não foi validado |
| GS1-128 | `(AI)dado…`, máx. 48 caracteres; verificador dos AIs 00/01/02; demais AIs pelo lint GS1 do codificador | **imagem** + texto legível com parênteses | FNC1 nativo muda entre linguagens e não foi validado |

- Lista das simbologias como imagem: `RASTER_1D` em `engine/src/barcode/encode.ts`. Uma simbologia só sai
  dessa lista com validação física do comando nativo.
- Imagem com módulo inteiro em dots, escolhido pelo resolver: a etiqueta sai idêntica à prévia.
  Sem barras de proteção (bearer bars) no ITF-14 — usar só se o cliente exigir para caixa de papelão.
- Testes: `engine/test/barcode-gs1.test.ts` (11) e `exporters/test/symbologies.test.ts` (10). Suíte do
  designer: **151 passando** (engine 119, exporters 23, print-server 9); `typecheck` sem erros.
- Etiqueta de teste física (`designer/tools/etiqueta-teste-fisico.ts`) ganhou a faixa de baixo com os três
  códigos (só em rolo de 150 mm).
- **PDF417: FEITO (2026-10-19, depois do push `29b801c`).** Retangular: matriz real (`rowmult: 1`, uma
  entrada por linha) com linhas de `PDF417_ROW_MODULES` = 3 módulos (mínimo da norma). O módulo tem de
  caber na largura (colunas) e na altura (linhas × 3). Nas impressoras vai como **imagem** (`RASTER_2D`,
  mesmo critério: `^B7`/`PDF417`/`b` nativos não validados) e gira com o objeto; QR e DataMatrix não
  mudaram. Prévia (canvas, SVG, PDF) desenha as linhas com a altura certa. `symbolBox2D()` dá a caixa
  ocupada a todos. Testes: `exporters/test/pdf417.test.ts` (11).

Texto original do passo:

- **Faltam:** EAN-8, GS1-128 (com AIs, ex. `(01)(17)(10)`), ITF-14, PDF417.
  Hoje só existe o dígito verificador GS1 para EAN-13/UPC-A (`barcode/checksum.ts`).
- **Fazer:** uma simbologia por vez em `engine` + ZPL/TSPL/EPL/SVG/PDF.
- **Aceite:** testes de dígito verificador com valores conhecidos; recusa de valor inválido com
  mensagem clara; leitura física pelo CF550 de cada uma (mesmo procedimento do passo 1).

### Passo 3 — Planilha Excel (`.xlsx`) como fonte de dados

- **Hoje:** só CSV (inclusive CSV salvo pelo Excel); `.xlsx` não é lido.
- **Decidir antes:** o cliente realmente entrega `.xlsx`? Se não, este passo não é feito.
- **Aceite:** primeira aba lida com cabeçalho; datas e números do Excel convertidos sem perder zeros à
  esquerda (EAN como texto); planilha vazia/corrompida recusada com motivo.

### Passo 4 — Numeração contínua entre lotes — FEITO (2026-10-19)

- Contador persistente por template no print-server (`counters.json`, gravação atômica), regra
  `NEVER` ou `DAILY` (dia local), ajuste manual por `PUT /api/counters/:templateId` (`next`, `reset`,
  validados, com token); `GET /api/counters` lista.
- **Mudança de regra em relação ao texto abaixo, de propósito:** a faixa é **reservada na criação do
  job** e gravada nele (`spec.seqStart`), não avançada só no `SENT`. Motivo: avançando no `SENT`, dois
  jobs criados juntos, ou um job cancelado e refeito, poderiam receber os **mesmos números**; número
  repetido em etiqueta de rastreio é pior que lacuna. Custo: job cancelado deixa lacuna, visível na
  faixa do job (`seqRange` no resumo) e na auditoria (`seqIndex` por etiqueta).
- Retomar/reenviar usa a faixa gravada: mesmos números. Job recusado (roteamento inválido) e template
  sem `SEQ` não consomem número. Reserva em fila (sem corrida entre requisições).
- Só o `SEQ` soma a faixa (`seqOffset`); `$index`/`$row` continuam a posição dentro do lote.
- Testes: `engine/test/sequence.test.ts` (6) e `print-server/test/sequence.test.ts` (5, ponta a ponta com
  impressora TCP falsa conferindo os números nos bytes, retomada, concorrência, persistência ao religar).
  Suíte: **188 passando** (engine 140, exporters 34, print-server 14); `typecheck` e `build` sem erros.
- **Tela do contador — FEITA (2026-10-20, sem commit):** aba Imprimir → seção "Numeração (SEQ)",
  só aparece se o template aberto usa `SEQ`. Mostra a posição atual (já com reinício diário, via
  `nextSequenceStart` do engine, a mesma regra da reserva), o último dia de lote, a prévia do que a
  próxima etiqueta imprime em cada objeto com `SEQ`, e ajusta posição e regra (`PUT /api/counters`).
  Voltar o contador mostra quais posições serão repetidas e só salva com a caixa "Sei que vai repetir
  números" marcada. A lista de jobs mostra a faixa `SEQ pos. X–Y` de cada job.
- **Correção junto (print-server):** `next` manual agora grava o dia de hoje. Antes, num contador
  `DAILY` cujo último lote era de outro dia, o ajuste era ignorado e a próxima reserva voltava a 0.
  Teste novo em `print-server/test/sequence.test.ts`.
- Suíte: **190 passando** (engine 141, exporters 34, print-server 15); `typecheck` e `build` sem erros.
  Conferido no navegador (designer + print-server locais, sem impressora): ajuste 0→120 salvo com
  prévia `000121`; volta 120→0 bloqueada até confirmar; seção some em template sem `SEQ`. O contador
  de teste foi devolvido a 0.

Texto original do passo:

- **Hoje:** `SEQ(início, passo, dígitos)` conta pela posição **dentro do lote** — recomeça a cada lote.
- **Fazer:** contador persistente por template no `print-server`, avançado só depois de confirmação
  de envio (`SENT`), com regra de reinício (nunca / diário / manual).
- **Aceite:** dois lotes seguidos continuam a numeração; lote interrompido retoma sem pular nem repetir;
  reimpressão usa o número original (não avança o contador).

### Passo 5 — Biblioteca de rolos e impressoras

- **Fazer:** tamanhos cadastrados por cliente (ex. 40×25, 60×40), com DPI, margens, gap e linguagem
  da impressora; ao escolher o rolo, o designer ajusta a área de desenho.
- **Aceite:** trocar de rolo não move objetos para fora da área sem aviso; DPI errado é detectado
  no passo de validação antes de imprimir.

### Passo 6 — Designer → IMPRESSÃO A no CF550

- **Por quê:** é o diferencial real em relação a soluções de desktop — o template feito no PC imprime
  pelo coletor.
- **Fazer:** definir o contrato (template compilado + dados) em `docs/contratos/openapi.yaml` antes de
  qualquer código; depois, envio do template ao app e impressão por ele.
- **Aceite:** a etiqueta do passo 1, enviada do PC, sai igual pela MB241 via CF550; template com versão
  e hash conferidos no aparelho; sem rede, o app recusa com mensagem clara (não imprime versão antiga
  sem avisar).

**Contrato escrito (2026-10-20, sem código):** `docs/contratos/designer-coletor.openapi.yaml`. Fica num
arquivo separado de `openapi.yaml` porque aquele é a API de nuvem, com outro servidor e outra
autenticação. Decisões tiradas do código:

| Decisão | Por quê |
|---|---|
| O PC compila um **pacote**: comandos prontos (ZPL/TSPL, por DPI) + lacunas; o app só valida, escapa e concatena | O app Android não tem o motor do designer (expressões, quebra de linha, auto-fit, medição). Lógica nos dois lados = etiquetas diferentes |
| **Vetores de conformidade** no pacote (valores → SHA-256 dos bytes); o app confere ao receber | Prova no próprio aparelho que o app monta exatamente os bytes do PC |
| v1 recusa ao publicar: `SEQ`, datas, expressões/pipes, auto-fit/várias linhas, imagem de campo, DataMatrix/PDF417/ITF-14/GS1-128/Code 39 dinâmicos | A geometria deles depende do valor ou precisa do motor. Objeto **estático** de qualquer tipo vai (vira comando pronto) |
| Code 128 dinâmico com largura de módulo congelada para `maxLength` | No designer o módulo depende do tamanho do valor |
| Texto dinâmico com `maxLength` conservador (glifo mais largo); passou, recusa, nunca corta | `^FB` de 1 linha sobrepõe o excesso |
| Hash e assinatura sobre os bytes de `packageJson` (string), não sobre JSON canônico | O Kotlin não precisa reproduzir o `canonicalJson` do JS |
| Assinatura ES256, chave pública só pelo provisionamento | Mesmo algoritmo da licença do app (`EcdsaLicenseTokenVerifier`); pacote adulterado é recusado mesmo em HTTP |
| Administração só em 127.0.0.1:7171 (token atual); coletor em listener separado na LAN (7172), desligado por padrão, token por aparelho, só leitura | O token de administração nunca sai do PC |
| Conferência de versão a cada impressão; sem resposta em 3 s, não imprime | Aceite do passo: "não imprime versão antiga sem avisar" |
| Relatório de impressão best-effort e idempotente (`reportId`) | Mesma auditoria SHA-256 por etiqueta do PC, sem travar a impressão |
| **O pacote vira template da ponte**, usado por qualquer app chamador via `IPrintBridgeService.printJob` (`DocumentType.TEMPLATE`, `templateId` + `variables`), não só pela tela do IMPRESSÃO A | O APK é a ponte de impressão do coletor (definição do usuário, 2026-10-20). A API já tem esse tipo e os erros `UNKNOWN_TEMPLATE`, `MISSING_VARIABLES`, `INVALID_VARIABLE_VALUE`, `LANGUAGE_MISMATCH`. Única adição: `TEMPLATE_VERSION_UNCONFIRMED` |
| ZPL/TSPL bruto, PDF, imagem, Compartilhar e impressão do sistema ficam intocados | O passo 6 só soma uma fonte de templates; nada muda nos caminhos atuais da ponte |

**Pendências (decisão do usuário antes do código):**
- **P1. Sem rede = nunca imprime?** O contrato segue o aceite ao pé da letra. Como o pacote é usado por
  todos os apps pela ponte, uma queda do Wi-Fi para **toda** impressão de template do designer no
  coletor. Os outros caminhos (ZPL bruto, PDF) continuam. Alternativa: aceitar a versão guardada se a
  última conferência bem-sucedida tiver menos de N minutos, registrando isso no relatório. Não depende de
  tela, então serve também para app chamador.
- **P5. "Qualquer app" pela API local.** Hoje a API AIDL e o intent exigem a permissão `signature`: só apps
  assinados com a chave da ATHOS usam (decisão da Fase 1, `android/docs/entrega/FASE1-PRINT-BRIDGE-CORE.md`).
  App de terceiro (WMS, ERP de outro fornecedor) só chega pela impressão do sistema ou pelo Compartilhar
  (PDF/imagem). Abrir para terceiros é mudança de segurança: exigiria lista de apps autorizados por pacote +
  certificado (pelo MDM) no lugar de `signature`. Fora do passo 6; decisão sua.
- **P2. HTTP sem TLS na rede da loja.** O pacote é assinado, mas o token do coletor trafega aberto (só dá
  leitura de templates). TLS com certificado fixado no app fica para v2? O Android também precisa liberar
  cleartext para o IP do PC (`network_security_config`).
- **P3. Linguagem do MB241 no app:** TSPL (como o plano diz no passo 1) ou ZPL (o que a ponte Carrefour
  mandou em 2026-09-29)? O pacote pode levar os dois alvos; falta saber qual publicar por padrão.
- **P4. Numeração (`SEQ`) no coletor:** fora do v1. Se precisar, o contador do PC teria que reservar faixa
  pela rede (rota nova).

Próximo, depois das pendências: compilador de pacote + rotas no print-server (TypeScript, com testes
contra os vetores), depois o lado Android.

## 5. Hifenização em português — FEITO (2026-10-19, sem commit)

Segunda análise de referência recebida no mesmo dia ("gap do seu app × padrão enterprise") supunha um
app de formulário fixo, sem ZPL, sem dados e sem lote. Conferido no código: tudo isso já existe
(seção 1). As únicas lacunas reais dela eram **hifenização** (feita aqui) e **PDF417** (passo 2). O
código ZPL proposto nela repetia erros da seção 3 (`^^`, `^A` com `B`/`N` como negrito, `^BC` sem `^BY`).

- `engine/src/layout/hyphenate-pt.ts`: pontos de quebra por regras de sílaba do português, versão
  conservadora — não separa vogais seguidas (ditongo × hiato exige dicionário), nem ch/lh/nh/qu/gu nem
  encontros com l/r; separa rr, ss, sc, sç, xc; mínimo de 2 letras de cada lado; palavra com número
  ou símbolo (SKU, código, URL) não é hifenizada.
- `wrapText`/`fitText` aceitam `hyphenate`; `TextObject.hyphenate` é **opcional e desligado**: templates
  salvos antes não têm o campo e quebram exatamente como antes (teste dedicado).
- Designer: caixa **Hifenizar** no painel do texto.
- Testes: `hyphenate.test.ts` (23: 15 palavras conhecidas, vogais, bordas, códigos, equivalência com a
  opção desligada, auto-fit, template antigo). Suíte do designer: **130 passando** (engine 108,
  exporters 13, print-server 9); `npm run typecheck` sem erros.
- **Não validado na impressora** — entra na etiqueta do passo 1 quando a TSC estiver ligada.

## 6. Itens de outra análise de referência — 2026-10-19 (sem commit)

Lista recebida: dados fantasma, colisão, desfazer/refazer, salvar/abrir template, prévia com margem.
Conferido no código: **dados fantasma** (50 linhas de `generateMockRows` desde a abertura), **desfazer/
refazer** (Ctrl+Z/Y, 100 passos) e **prévia com margem** (simulador) já existiam. Faltavam dois:

- **Alerta de código invadido (`BARCODE_OVERLAP`)**, em `checkGeometry`. Diferente da proposta (marcar
  qualquer sobreposição): só dispara quando algo invade a área de um **código de barras**, em qualquer
  ordem de camada — na térmica o preto soma, não existe "por cima". Moldura vazia que só contorna o
  código não conta; a que cruza com a borda conta. Caixas visíveis já giradas. Testes: `overlap.test.ts` (8).
  O alerta achou dois erros na própria etiqueta de teste do passo 1 (texto sobre o QR, texto girado
  sobre o DataMatrix) — corrigidos no gerador, que agora também lista os alertas de validação e sai limpo.
- **Template como arquivo** (`engine/src/templates/file.ts`, aba Templates › Arquivo): exporta
  `<nome>.athoslabel.json` com formato, versão do formato e SHA-256 do template; ao abrir, valida tudo
  antes (JSON, formato, versão mais nova, medidas, tipos, simbologias, imagem, ids repetidos, hash) e
  recusa com o motivo. Importado ganha id novo (nunca sobrescreve a biblioteca) e sai sem vínculo de
  herança. Base do passo 6 (o coletor poderá conferir o mesmo hash). Testes: `template-file.test.ts` (7).

Suíte do designer: **177 passando** (engine 134, exporters 34, print-server 9); `typecheck` e
`npm run build` sem erros.

## 7. Fora deste plano (decisão posterior)

Colaboração em tempo real, sugestão de layout por IA, motor em WebAssembly e backend em nuvem
apareceram no plano de referência. Só entram com pedido de cliente e decisão registrada.
