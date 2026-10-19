# impresao2.0

Plataforma de impressão de etiquetas da ATHOS: o **Print Bridge** (Android e Windows), que leva
o job até a impressora térmica, e o **Designer**, que desenha a etiqueta. Uma versão por cliente,
um núcleo só.

De onde veio cada parte, e o que ficou no projeto anterior como referência: [PROVENIENCIA.md](PROVENIENCIA.md).

## Estrutura

| Pasta | O que é | Stack |
|---|---|---|
| `android/` | Print Bridge Android (`:app`), API AIDL para outros apps (`:printbridge-api`), app de teste (`:sample-client`), scripts de homologação e instalação em escala (`tools/`) | Kotlin, Compose, Room, WorkManager, Hilt |
| `windows/` | Print Bridge Windows | .NET 10, xUnit |
| `backend-reference/` | Referência de ativação, licença e auditoria (ainda não é um servidor em produção) | .NET 10, xUnit |
| `designer/` | Designer de etiquetas (ver abaixo) | TypeScript, React, Konva, Zustand, bwip-js, pdf-lib, Vitest |
| `docs/contratos/` | `openapi.yaml` | |

## Versões por cliente (Android)

Cada cliente é um *product flavor* em `android/app/build.gradle.kts`:

| Flavor | Marca | Nome no launcher | Troca de marca no app |
|---|---|---|---|
| `carrefour` (padrão) | Carrefour | Carrefour Print Bridge | bloqueada |
| `roldao` | Roldão | Roldão Print Bridge | bloqueada |
| `assai` | Assaí | Assaí Print Bridge | bloqueada |
| `generico` | ATHOS | A.print.Bridge | liberada (demonstração) |

**O `applicationId` é o mesmo em todos (`br.com.atos.printbridge`).** Os apps clientes da API se
ligam a esse pacote, e a permissão `signature`, a licença por aparelho e a config do MDM são
indexadas por ele. Cada loja roda um cliente só. Por isso, instalar o APK de outro cliente no
mesmo aparelho **substitui** o atual.

**Para adicionar um cliente:**
1. Crie o flavor em `productFlavors`.
2. Adicione a entrada em `BrandId`/`BrandPresets` (`ui/brand/BrandPresets.kt`).
3. Crie `app/src/<flavor>/res/values/strings.xml` com o `app_name`.
4. Rode `test<Flavor>DebugUnitTest`: o `BrandPresetsTest` confere a chave, o contraste AA e a trava.

## Designer de etiquetas

| Pacote | O que faz |
|---|---|
| `packages/engine` | Núcleo puro (roda no navegador, no Web Worker e no Node): modelo de objetos em mm (texto, código 1D, QR/DataMatrix, imagem, caixa), templates `{{CAMPO \| pipe}}` com mini-interpretador de expressões (`IF`, `SEQ`, `DATEADD`…, sem `eval`), CSV/JSON/API REST/dados fictícios, mapeamento e saneamento de dados, auto-fit de texto, Layout Resolver, Sanity Check, versões, variantes com herança, Smart Guides, alinhar/distribuir, orquestrador de impressão |
| `packages/exporters` | ZPL, TSPL (TSC), EPL2, SVG e PDF a partir do mesmo layout resolvido; template compilado com cache (10.000 etiquetas ZPL em ~0,2 s) |
| `apps/web` | Editor: canvas, propriedades, dados, exportar (lote no Web Worker), simulador de impressora, kit de marca, templates/versões/A-B, imprimir |
| `apps/print-server` | Serviço local (127.0.0.1, token) que imprime em lotes via TCP 9100, com checkpoint, retomada, roteamento por regra, status da impressora e auditoria SHA-256 por etiqueta |

Para imprimir pelo designer: `npm run print-server` (o terminal mostra onde fica o token) e, na aba
**Imprimir**, conectar com esse token e cadastrar as impressoras.

> As saídas ZPL/TSPL/EPL foram testadas contra impressora simulada (servidor TCP). Antes de uso em
> loja, validar na TSC MB241: posição de texto girado, deslocamento do QR e tamanho da fonte residente.

## Comandos

```powershell
# Android (em android/) — com flavors, o nome da tarefa leva o cliente
.\gradlew :app:testCarrefourDebugUnitTest      # testes de um cliente
.\gradlew :app:assembleCarrefourDebug          # APK -> app/build/outputs/apk/carrefour/debug/
.\gradlew :app:assembleDebug                   # APK debug de todos os clientes
.\gradlew :app:lintCarrefourDebug
pwsh -File tools\homologacao\empacotar-entrega.ps1 -Cliente assai
$env:PB_CLIENTE = 'assai'                      # scripts de homologação usam o APK desse cliente

# Windows e backend-reference
dotnet test windows\CarrefourPrint.Bridge.slnx
dotnet test backend-reference\CarrefourPrint.Backend.Reference.slnx

# Designer (em designer/)
npm install
npm test
npm run typecheck
npm run dev                                    # http://localhost:5173
npm run print-server                           # http://127.0.0.1:7171
npm run build
```

> Atenção: `gradlew testDebugUnitTest`, **sem** o nome do cliente, NÃO roda os testes do `:app`.
> Ele só roda os módulos sem flavor. Use sempre `test<Cliente>DebugUnitTest`.

## Regras que continuam valendo

- `SENT` significa "bytes entregues à impressora", não "etiqueta impressa".
- Trava C2: um release com a chave pública de teste da licença falha o build, para qualquer cliente.
- O keystore de release fica fora do git (`android/keystore/`) e não muda.
