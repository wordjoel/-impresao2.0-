# Roteiro de teste — Fase 1 (Print Bridge Core) no coletor

Para quem vai homologar. Três níveis, do mais rápido ao definitivo. Os níveis A e B já rodaram no
coletor de referência **Bluebird CF550 (Android 11, serial CF550A4LAWBB340)** em 2026-09-21 — ver
"Resultado de referência" no fim.

**Regra de ouro:** "Enviado"/`job-completed` = a impressora **aceitou os bytes** pelo socket. Só a
etiqueta na mão prova impressão (nível C).

## Pré-requisitos

- PC Windows com **PowerShell 7** (`pwsh`) — a impressora simulada exige; os demais scripts também
  rodam no Windows PowerShell 5.1. Java 17+ para ativar a licença de laboratório (o token é gerado por coletor, não vale em outro aparelho).
- `adb` (Android Platform-Tools) no PATH ou na pasta `ADB\` do pacote.
- Coletor com **Depuração USB** ligada e este PC autorizado (`adb devices` mostra `device`).

## Nível A — instalação (1 comando, ~1 min)

`INSTALAR.bat` (ou `pwsh -File tools\homologacao\instalar-coletor.ps1`):

1. identifica o coletor (modelo, Android, IP Wi-Fi);
2. instala/atualiza **A.print.Bridge (debug)** e **PB Cliente Teste** — sem desinstalar, sem perder impressoras/histórico já configurados;
3. confirma que o PB Cliente Teste recebeu a permissão `signature` (mesma assinatura);
4. concede notificações (Android 13+);
5. ativa a **licença de laboratório** por ADB (sem digitar token; só existe no build debug).

Esperado: tudo `[OK]`.

**Na 1ª instalação o Google Play Protect abre um diálogo no coletor** ("Enviar o app para uma
verificação de segurança?"): toque em **"Não enviar"** — é APK interno de teste. Sem resposta, a
instalação falha por tempo; o script avisa e basta rodá-lo de novo.

Se o script disser que um app está **DESATIVADO**, alguém o desativou no coletor (Configurações >
Apps). Confirme que ninguém está usando o coletor para outra coisa e reative com o comando que o
script mostra (`adb shell pm enable ...`).

## Nível B — teste de fumaça automático, sem impressora (1 comando, ~2 min)

`TESTE-FUMACA.bat` (ou `pwsh -File tools\homologacao\teste-fumaca-coletor.ps1 -TestarOffline`).

Sobe uma "impressora" simulada **dentro do próprio coletor** (`nc` em 127.0.0.1:9105, servidor
multi-conexão) e usa o PB Cliente Teste — um app de verdade chamando a API AIDL — para mandar:

| Ação | Critério do prompt | O script confere |
|---|---|---|
| Etiqueta SIF (template) | 1 | jobId + `job-completed` + bytes recebidos |
| PDF 1 página | 2 | `job-completed` + gráfico `^GFA` |
| PDF 3 páginas | 3 | `page-x-of-n` 1/3→3/3 em ordem + `document-finished 3/3` |
| Imagem | 2 | `job-completed` + `^GFA` (dithering) |
| ZPL bruto | — | `job-completed` |
| Template sem variáveis | 6 | recusa com código `MISSING_VARIABLES`, nada impresso |
| **Impressora desligada → liga** | 4 | retries 2s/4s/8s com "Conexão recusada" → entregue sozinho |

Esperado: `██ PASSOU ██`. Não mexa no coletor durante o teste (ele traz o PB Cliente Teste para a frente).

## Nível C — impressora TSC real (definitivo)

1. Coletor e TSC na mesma rede; anote IP da TSC, DPI (203/300) e papel (ex.: 100x72).
2. `pwsh -File tools\homologacao\teste-fumaca-coletor.ps1 -Ip <IP_TSC> -Protocolo ZPL -Dpi 203 -Papel 100x72`
   (TSC sem emulação ZPL: `-Protocolo TSPL` — a etiqueta SIF é ZPL e fica fora do roteiro; PDF/imagem/bruto saem em TSPL `BITMAP`).
3. Conferir na mão e registrar:

| # | Verificação | Resultado |
|---|---|---|
| C1 | Etiqueta SIF impressa, 4 códigos de barras + QR **legíveis por leitor** | |
| C2 | "Peso Líquido" com **í correto** (senão: adicionar `^CI28` ao template) | |
| C3 | Orientação correta (senão: rolo invertido ou `^POI`) | |
| C4 | PDF 1 página nítido, sem cortes, centralizado | |
| C5 | PDF 3 páginas saem **as 3, em ordem**, sem pedir nada ao operador | |
| C6 | Imagem com gradiente reconhecível (dithering) | |
| C7 | TSPL: imagem **não sai em negativo** (senão: inverter polaridade em `TsplGraphicEncoder`) | |
| C8 | Desligar a TSC → mandar etiqueta → notificação "Tentando novamente (n/5)" → ligar → sai sozinha | |
| C9 | TSC desligada o tempo todo → após 5 tentativas notificação **"Falha na impressão"** persistente | |
| C10 | Coletor gerenciado por MDM/EMM: instala e imprime sem permissão especial | |

Evidência: foto da etiqueta + `adb logcat -d -s PrintBridge PBClienteTeste > evidencia.txt`.

## Uso manual do PB Cliente Teste

Abra "PB Cliente Teste" no coletor: informe IP/porta/linguagem/DPI/papel → **Cadastrar impressora**
→ botões 1-6. O log mostra o jobId e cada callback (`⇐ job-retrying`, `⇐ job-completed`...).

## Impressora simulada no PC (opcional)

`IMPRESSORA-SIMULADA.bat` escuta TCP 9100 no PC, grava cada job e **renderiza o `^GFA`/`BITMAP` em
PNG** (para ver o PDF rasterizado sem TSC). O coletor precisa alcançar o PC pela rede:

- rode uma vez, como administrador: `New-NetFirewallRule -DisplayName 'PrintBridge impressora simulada 9100' -Direction Inbound -Protocol TCP -LocalPort 9100 -Action Allow -Profile Any`
- cadastre no coletor o **IP do PC**, porta 9100.

**Não use `adb reverse` para testar impressão:** o túnel aceita a conexão mesmo com o PC desligado
e descarta conexões curtas — dá "enviado" falso (verificado: 10/10 envios `nc` perdidos).

## Limites conhecidos desta entrega

- Build **debug**: licença de laboratório (chave de teste). Release exige a chave pública real do
  servidor de licenças — o gate C2 do build bloqueia release com a chave de teste, de propósito.
- O token de licença **não é amarrado ao deviceId** do coletor (lacuna de segurança pré-existente,
  a corrigir antes de produção): um token vale em qualquer aparelho.
- Fila por impressora é estritamente ordenada: com a impressora fora, um job só dá lugar ao
  próximo após esgotar as 5 tentativas (~62s). N jobs parados = até N×62s até todos falharem.

## Resultado de referência (2026-09-21, Bluebird CF550, Android 11)

| Item | Resultado |
|---|---|
| Testes JVM/Robolectric | 259/259 |
| Instrumentados no coletor (PdfRenderer real + E2E AIDL) | 5/5 |
| Roteiro PB Cliente Teste (SIF, PDF 1/3 pág., imagem, bruto, erro, status) | todos OK, callbacks corretos |
| Página 100x72 @203dpi | 113.901 bytes `^GFA`; rasterizar+enfileirar ~200-600 ms |
| Offline → volta | 4 recusas (2/4/8/16s) e entregue na 5ª tentativa (30,5 s) |
| 5 falhas | `job-failed` + notificação "Falha na impressão … (5 tentativas)" |
| Pacote de entrega (INSTALAR + TESTE-FUMACA -TestarOffline) | **PASSOU** (18:1x, a partir da pasta do pacote) |
| TSC física | **pendente** (nenhuma impressora na rede do teste) |

### Rodada completa sem impressora (2026-09-21, 22h, master `f9058f6`, mesmo CF550)

Tudo o que dá para validar sem a TSC, rodado de novo do zero (sem cache do Gradle):

| Item | Resultado |
|---|---|
| Testes JVM/Robolectric (debug **e** release) | 272/272 em cada variante |
| Lint (app, printbridge-api, sample-client) | 0 erros (avisos: versões de dependência, `DataExtractionRules` no sample client) |
| Build de release (R8) | compila; a trava C2 **recusa** o release com a chave de licença de TESTE (esperado até existir a chave real do backend) |
| Instrumentados no coletor | 5/5 |
| Fumaça ZPL 203dpi 100x72 + offline→volta | **PASSOU** (3 retries 2/4/8s, entregue sozinho em 14,6 s) |
| Fumaça ZPL 300dpi 100x72 | **PASSOU** (1,25 MB, 5 `^GFA`) |
| Fumaça TSPL 203dpi 100x72 | **PASSOU** (5 `BITMAP` com `SIZE 100 mm,72 mm`; SIF recusado com `LANGUAGE_MISMATCH`) |
| Fumaça TSPL 300dpi 100x150 | **PASSOU** (5 `BITMAP` com `SIZE 100 mm,150 mm`) |
| 5 falhas → `FAILED` | retries 2/4/8/16s medidos, `job-failed` aos 30,4 s + notificação persistente |
| Render dos bytes recebidos (PNG) | 792x575 @203 e 1176x1771 @300: texto nítido, acentos, dithering, sem negativo, páginas em ordem |

Correção no script de fumaça nesta rodada: em TSPL ele esperava `MISSING_VARIABLES`, mas o template
SIF é ZPL e a recusa correta ali é `LANGUAGE_MISMATCH` (a linguagem é checada antes das variáveis);
e ele não conferia gráfico nenhum em TSPL — agora exige `BITMAP` com o `SIZE` do papel escolhido.

Observação para o nível C: a largura em pontos é arredondada para baixo a bytes inteiros
(100 mm @203dpi = 799 pontos → 792), então a borda direita perde até 7 pontos (~0,9 mm). Conferir
na etiqueta real se isso corta algo encostado na margem.

### Reteste de confirmação (2026-09-22, master `5ab262f`, mesmo CF550)

Rodada para confirmar o script corrigido num coletor "frio" (apps já instalados da rodada anterior,
sem reinstalar), antes de levar a correção para `master`:

| Item | Resultado |
|---|---|
| Fumaça ZPL 203dpi 100x72 + offline→volta | **PASSOU** (9 jobs, 797.869 bytes, 7 `^GFA`; 3 retries 2/4/8s, entregue sozinho em 14,4 s) |
| Fumaça TSPL 203dpi 100x72 | **PASSOU** (6 jobs, 284.979 bytes, 5 `BITMAP` com `SIZE 100x72`; SIF recusado com `LANGUAGE_MISMATCH`) |
| Fumaça TSPL 300dpi 100x150 | **PASSOU** (6 jobs, 1.302.054 bytes, 5 `BITMAP` com `SIZE 100x150`) |
| Recusa em ZPL | `MISSING_VARIABLES` com as 7 variáveis faltantes listadas, nada impresso |
| PDF 3 páginas (ZPL e TSPL) | `page-x-of-n` 1/3→3/3 em ordem + `document-finished 3/3` nas duas linguagens |

Estado do coletor auditado antes de começar: `br.com.atos.printbridge.debug` e
`...sampleclient` com `enabled=0` (padrão, ativos) — os apps que alguém havia desativado na rodada
anterior foram reativados. `com.athos.integracaoa` (projeto que compartilha o coletor) também ativo
e intocado.

**Nível C continua pendente por falta de hardware, não por software:** varredura TCP 9100 em toda a
faixa `192.168.70.0/24` (PC `.106`, coletor `.109`) não encontrou nenhum dispositivo escutando.
Sem TSC na rede, C1-C10 não têm como ser executados.
