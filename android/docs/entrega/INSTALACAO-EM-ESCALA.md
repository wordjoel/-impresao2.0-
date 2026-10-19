# Instalação em escala — A.print.Bridge (387 lojas / ~5.800 coletores)

Objetivo: instalar, configurar e atualizar o app **sem tocar em cada coletor**. Três caminhos, que
usam **a mesma configuração** (loja + impressoras + ajustes):

| Caminho | Quando | Toque no coletor |
|---|---|---|
| **A. MDM/EMM** (Managed Configurations) | Produção, frota inteira | Nenhum |
| **B. Bancada de preparação** (hub USB) | Piloto, lojas sem MDM, reposição de aparelho, laboratório | Só plugar o cabo |
| C. Manual (tela Impressoras) | Exceção / suporte | Total |

A configuração **nunca carrega licença, token ou chave**. A ativação em escala é o `PairingCode`/QR
contra o backend (`CARREFOUR-PRINT-PROVISIONING.md`), que depende do backend de produção (C1 em
`CARREFOUR-CYBER-SECURITY-GATE.md`). Até lá, piloto usa o build debug com licença de laboratório.

## O que é configurado

Chaves idênticas no MDM e no `provisioning.json` da bancada (esquema em
`app/src/main/res/xml/app_restrictions.xml`):

| Chave | Tipo | Exemplo | Observação |
|---|---|---|---|
| `store_code` | texto | `4521` | Casa a loja existente pelo código |
| `store_name` | texto | `Loja Centro` | |
| `printers` | lista | — | cada item: `name`, `ip`, `port` (9100), `language` (ZPL/TSPL), `model`, `dpi` (203/300), `paper` (`100x72`), `is_default` |
| `replace_printers` | sim/não | `false` | `true` = o MDM é a fonte da verdade: impressoras da loja fora da lista são removidas |
| `max_attempts` | número | `5` | tentativas por job (1-20) |
| `retry_base_ms` | número | `2000` | espera base do backoff (0-60000) |
| `history_retention_days` | número | `30` | 0-365 |
| `admin_pin` | oculto | `4321` | PIN do modo administrador, hasheado na hora no coletor |

Regras de aplicação (garantidas por teste, `ApplyProvisioningUseCaseTest`):

- **Idempotente:** impressora casada por **IP:porta**, loja por **código** — reaplicar atualiza, nunca duplica; o ID da impressora é preservado.
- **Tudo ou nada:** qualquer item inválido (IP, porta, DPI, papel, duas padrão, IP repetido) recusa a configuração inteira e nada muda — nunca um coletor "meio configurado".
- Uma impressora só vira a padrão sozinha; nunca há duas padrão.
- O app aplica **ao abrir** e **na hora em que o MDM muda a configuração** (`ACTION_APPLICATION_RESTRICTIONS_CHANGED`); só reaplica se algo mudou.
- Com MDM configurado, ele é a autoridade: o arquivo da bancada é ignorado.

## Caminho A — MDM/EMM (produção)

1. **Assinar o APK de release** com o keystore ATHOS e a **chave pública real** do servidor de licenças (o gate C2 do build bloqueia release com a chave de teste).
2. **Publicar** o APK no MDM como app privado/gerenciado (Managed Google Play privado, ou upload direto no EMM). Instalação **silenciosa e obrigatória** para o grupo de coletores.
3. **Grupos:** um grupo (ou tag) de coletores **por loja**.
4. **Configuração por loja:** gerar os arquivos a partir da planilha única de lojas:
   ```
   pwsh -File tools\instalacao-em-escala\gerar-config-mdm.ps1 -Planilha lojas.csv -Saida mdm-config
   ```
   Sai um `loja-<código>.json` por loja no formato `androidenterprise#managedConfiguration`
   (Intune: *App configuration policies → Managed devices → Configuration settings format: Enter JSON data*).
   EMMs com editor de formulário mostram os mesmos campos, lidos do esquema do app.
5. **Notificações** (Android 13+): conceder `POST_NOTIFICATIONS` por política de permissão do MDM, para o operador ver "enviado/falha" sem diálogo.
6. **Play Protect:** apps entregues pelo MDM não passam pelo diálogo "Enviar para verificação".

Mudança de impressora numa loja = editar o JSON daquela loja no MDM. Os coletores aplicam sozinhos.

## Caminho B — bancada de preparação (hub USB)

1. Planilha CSV (modelo: `tools/instalacao-em-escala/planilha-exemplo.csv`), **uma linha por impressora**:
   ```
   serial,store_code,store_name,printer_name,printer_ip,printer_port,language,model,dpi,paper,is_default
   CF550A4LAWBB340,4521,Loja Centro,Expedicao,10.1.2.50,9100,ZPL,TSC_ML241,203,100x72,true
   *,0001,Loja Padrao,Expedicao,192.168.70.50,9100,ZPL,TSC_ML241,203,100x72,true
   ```
   `serial` = `adb devices` do coletor; `*` = configuração para quem não está na planilha.
2. Plugar os coletores (depuração USB autorizada) e rodar:
   ```
   pwsh -File tools\instalacao-em-escala\instalar-lote.ps1 -Planilha lote.csv [-LicencaLab] [-Paralelo 8]
   ```
3. Para cada coletor, em paralelo: identifica → checa se o app foi desativado → instala/atualiza (dados preservados) → aplica a configuração da loja → (licença de laboratório, só debug) → volta à tela inicial.
4. Sai um **relatório CSV** (serial, modelo, Android, loja, impressoras, resultado, tempo) — é o registro do lote. Qualquer falha fica explícita na linha do coletor e o script termina com erro.

Primeira instalação com Play Protect ativo: o coletor mostra "Enviar o app para verificação?" → **Não enviar**.

## Plano de rollout recomendado

| Onda | Escopo | Critério para avançar |
|---|---|---|
| 0 — Laboratório | 1-3 coletores + TSC real | `ROTEIRO-TESTE-FASE1.md` nível C completo (etiqueta na mão) |
| 1 — Piloto | 1-2 lojas (~30 coletores) | 1 semana sem job perdido; falhas só de rede/impressora, visíveis ao operador |
| 2 — Regional | ~10% das lojas | Indicadores estáveis; nenhum coletor "meio configurado" |
| 3 — Nacional | Restante, em lotes por região | — |

**Atualização:** subir nova versão (versionCode maior) no MDM; instala por cima preservando fila,
histórico e configuração (migrações Room só aditivas). **Rollback:** republicar a versão anterior
exige desinstalar (Android não faz downgrade por cima) — por isso cada onda só avança com a anterior estável.

## Estado de validação (2026-09-21)

| Item | Evidência |
|---|---|
| Aplicação idempotente, tudo-ou-nada, padrão única, substituição | 9 testes JVM (`ApplyProvisioningUseCaseTest`) |
| MDM (Bundle) e bancada (JSON) geram a mesma configuração | `ProvisioningReadersTest` (Robolectric) |
| Bancada completa no coletor real (Bluebird CF550, Android 11) | `instalar-lote.ps1` → instalação + loja LAB01 + impressora (atualizada, sem duplicar: `+0 ~1 -0`) + licença, 25 s |
| Entrega pelo MDM real | **Pendente** — precisa de um EMM (ou TestDPC) com o coletor como dispositivo gerenciado |
