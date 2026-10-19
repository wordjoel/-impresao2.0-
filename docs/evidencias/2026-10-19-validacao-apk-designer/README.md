# Validação — APK IMPRESSÃO A no CF550 e funções novas do designer — 2026-10-19

Sem impressora (TSC fora do USB): nada foi impresso nem lido por scanner. O que está aqui é o que dá
para provar sem ela.

## APK (`br.com.atos.printbridge.debug`, flavor `generico`, SHA-256 `B1809531…2931`, instalado 18:37)

| Tela | Esperado | Resultado | Captura |
|---|---|---|---|
| Abertura | nome do app | **IMPRESSÃO A** | `01-abertura.png` |
| Configurações › Diagnóstico | "Marca ativa: IMPRESSÃO A" | **OK** | `03-config-diagnostico.png` |
| Sobre | título e "Cliente / marca" | **IMPRESSÃO A** nos dois | `04-sobre.png` |
| Gaveta de apps | ícone novo + nome | ícone impressora com selo "A" **OK**; nome **quebra no meio**: "IMPRESS / ÃO A" | `05-gaveta.png` |
| Tela inicial (barra de título) | — | **ainda "Print Bridge"**: com logotipo, a barra mostra `subBrandName` (padrão "Print Bridge"), não o `displayName` | `02-home.png` |

Ainda com o nome antigo (não alterados, fora do pedido): subtítulo da tela inicial, frase "Núcleo Atos
Print Bridge" em Sobre, título da página de teste e cabeçalho do pacote de "Exportar logs".

## Designer (prévia gerada pelo próprio motor, `06-previa-designer.png`)

- Caixa de 20 mm, Helvetica negrito 4,5 mm, texto "OFERTA PROMOÇÃO IMPERDÍVEL CHOCOLATE":
  - sem hifenização: `OFERTA / PROMO / ÇÃO / IMPERDÍ / VEL…` (corte no meio da palavra, sem hífen);
  - com hifenização: `OFERTA / PROMO- / ÇÃO IM- / PERDÍ- / VEL…` (sílabas corretas).
  - O texto não cabe nos dois casos → alerta `TEXT_OVERFLOW` (correto).
- EAN-8 `96385074`, ITF-14 `10012345678902`, GS1-128 `(01)10012345678902(17)261231(10)LOTE1`: barras e texto
  legível desenhados; nenhum alerta.
- Suíte do designer: 151 testes, 0 falhas; `typecheck` sem erros.

**Pendente:** imprimir na TSC MB241 e ler cada código com o scanner do CF550 (passo 1 do plano).
