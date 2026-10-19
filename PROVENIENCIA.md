# Proveniência

O `impresao2.0` é um projeto **novo e independente**. Nada aqui é sincronizado de volta com o
projeto anterior. O anterior serve apenas como **referência somente leitura**.

## De onde veio cada parte

| Pasta | Origem | Ponto exato |
|---|---|---|
| `android/` (app, printbridge-api, sample-client, tools, gradle) | `C:\projetos\fluxo de impresao` (`github.com/yoelzaiyt/atos-print-bridge`) | branch `fase-d/d2-printbridge-config-gerenciada`, commit `80b49aa` (15/10/2026), extraído com `git archive` |
| `windows/` | idem | idem |
| `backend-reference/` | idem | idem |
| `docs/contratos/openapi.yaml` | idem | idem |
| `designer/` | `C:\projetos\athos-label` (criado em 18/10/2026, nunca commitado lá) | cópia sem `node_modules`/`dist` |

O `80b49aa` foi escolhido por ser o ponto pushado mais completo do projeto anterior. Ele contém:
`integ/unificacao-master`, `feat/impressoras-reais` (TSC MB241 física), `feat/fase2-printservice`,
`feat/agente-matricula` e a homologação D-EMU (licença gerenciada).

**Ficou de fora:** 2 commits locais e não pushados de `feat/mdm-integration-poc` (`16327b2`,
`50b387e`, integração com o `mdm-device-gateway`). O MDM é um projeto à parte (`C:\projetos\MDM-ON-02`)
e não é misturado aqui.

## Linha de base (cópia sem nenhuma alteração, 18/10/2026)

| Parte | Comando | Resultado |
|---|---|---|
| Windows | `dotnet test windows/CarrefourPrint.Bridge.slnx` | 39 + 32 = 71/71 |
| backend-reference | `dotnet test backend-reference/CarrefourPrint.Backend.Reference.slnx` | 93/93 |
| designer | `npm test` + `npm run typecheck` | 19/19, sem erros de tipo |
| Android | `gradlew :app:testDebugUnitTest :app:assembleDebug :sample-client:assembleDebug` | 374/374, build verde |

## Alterações feitas aqui depois da linha de base (18/10/2026)

- **Flavors por cliente** (`carrefour`, `roldao`, `assai`, `generico`): ver README. Mesmo `applicationId`.
- `DEFAULT_BRAND_ID` passa a vir do flavor (`BuildConfig.CLIENT_BRAND_KEY`). No APK de cliente a
  marca é travada (`BRAND_LOCKED`) e o seletor some de Configurações.
- Trava C2 adaptada aos flavors: varre `outputs/apk/**/release/*.apk` e roda depois de qualquer
  `assemble*Release`. Teste negativo feito: um APK falso com a chave de teste foi recusado.
- Scripts `tools/homologacao`: caminho do APK por cliente (`-Cliente` / `$env:PB_CLIENTE`).
  Os 4 documentos que o pacote de entrega leva vieram para `android/docs/entrega/`.

Resultado: `test<Cliente>DebugUnitTest` dá 376/376 em cada um dos 4 flavors (374 + 2 novos de marca).
Também passaram `assembleDebug` dos 4, `lintCarrefourDebug` (0 erros) e `assembleAssaiRelease` com o gate C2.

## Documentação de referência (não copiada)

Os cerca de 40 `.md` da raiz do projeto anterior (homologações, modelo de segurança, licenciamento,
matriz de validação, white-label etc.) continuam em `C:\projetos\fluxo de impresao` e são
consultados lá. Os mais úteis:

- `CARREFOUR-PRINT-VALIDATION-MATRIX.md`: o que é PASS, BLOCKED ou NOT_TESTED, com evidência.
- `WHITE-LABEL-ARCHITECTURE.md`: contrato `BrandConfig` e regra de contraste.
- `VALIDACAO-TSC-2026-09-29.md`: prova física na TSC MB241.
- `CARREFOUR-PRINT-SERVER-LICENSING.md` e `CARREFOUR-CYBER-SECURITY-GATE.md`: licença e trava C2.
- `ROTEIRO-TESTE-FASE1.md` e `INSTALACAO-EM-ESCALA.md`: roteiros de campo.

## Restrições herdadas (continuam valendo)

- `applicationId` `br.com.atos.printbridge` e o keystore de release **não mudam**. Os aparelhos já
  instalados e os apps clientes da API dependem deles.
- `SENT` não é "impresso". Nenhuma tela ou relatório pode tratar os dois como iguais.
- A trava C2 continua ativa: release com a chave pública de teste da licença falha o build.
