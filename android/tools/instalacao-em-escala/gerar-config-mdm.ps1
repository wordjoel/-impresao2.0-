<#
.SYNOPSIS
  Gera, a partir da MESMA planilha da bancada, um arquivo de Managed Configuration por loja para
  colar/importar no console do MDM/EMM (387 lojas = 387 arquivos, nenhum digitado à mão).

.DESCRIPTION
  Formato "managedConfiguration" do Android Enterprise (Google Play EMM API), aceito como
  "JSON data" pelo Microsoft Intune e por outros EMMs que seguem o padrão:
    { "kind": "androidenterprise#managedConfiguration",
      "productId": "app:<pacote>",
      "managedProperty": [ {"key":"store_code","valueString":"4521"}, ... ] }
  As chaves são as de app/src/main/res/xml/app_restrictions.xml.

  Uma loja por store_code; o serial é ignorado aqui (no MDM a configuração vai para o GRUPO de
  coletores da loja, não para um aparelho).

.EXAMPLE
  pwsh -File tools\instalacao-em-escala\gerar-config-mdm.ps1 -Planilha lojas.csv -Saida mdm-config
  pwsh -File tools\instalacao-em-escala\gerar-config-mdm.ps1 -Planilha lojas.csv -Pacote br.com.atos.printbridge -SubstituirImpressoras
#>
param(
    [Parameter(Mandatory)][string]$Planilha,
    [string]$Saida = 'mdm-config',
    [string]$Pacote = 'br.com.atos.printbridge',
    [switch]$SubstituirImpressoras,
    [int]$MaxTentativas = 0
)
$ErrorActionPreference = 'Stop'

function P([string]$key, $value) {
    switch ($value) {
        { $_ -is [bool] } { return [ordered]@{ key = $key; valueBool = $value } }
        { $_ -is [int] } { return [ordered]@{ key = $key; valueInteger = $value } }
        default { return [ordered]@{ key = $key; valueString = "$value" } }
    }
}

$linhas = Import-Csv $Planilha | Where-Object { $_.store_code -and $_.serial -ne '*' }
if (-not $linhas) { throw "Nenhuma loja na planilha $Planilha (linhas com serial '*' são só para a bancada)." }
New-Item -ItemType Directory -Force -Path $Saida | Out-Null

$lojas = $linhas | Group-Object store_code
foreach ($loja in $lojas) {
    # Impressoras únicas por IP:porta (a mesma impressora pode aparecer em linhas de vários coletores).
    $impressoras = $loja.Group | Where-Object { $_.printer_ip } |
        Sort-Object printer_ip, printer_port -Unique |
        ForEach-Object {
            [ordered]@{
                managedProperty = @(
                    (P 'name' $_.printer_name)
                    (P 'ip' $_.printer_ip)
                    (P 'port' $(if ($_.printer_port) { [int]$_.printer_port } else { 9100 }))
                    (P 'language' $(if ($_.language) { $_.language.ToUpper() } else { 'ZPL' }))
                    (P 'model' $(if ($_.model) { $_.model } else { 'TSC_ML241' }))
                    (P 'dpi' $(if ($_.dpi) { "$($_.dpi)" } else { '203' }))
                    (P 'paper' $(if ($_.paper) { $_.paper } else { '100x72' }))
                    (P 'is_default' ([bool]($_.is_default -match '^(true|1|sim|s)$')))
                )
            }
        }
    $props = @(
        (P 'store_code' $loja.Name)
        (P 'store_name' $loja.Group[0].store_name)
        [ordered]@{ key = 'printers'; valueBundleArray = @($impressoras) }
        (P 'replace_printers' ([bool]$SubstituirImpressoras))
    )
    if ($MaxTentativas -gt 0) { $props += (P 'max_attempts' $MaxTentativas) }
    $doc = [ordered]@{ kind = 'androidenterprise#managedConfiguration'; productId = "app:$Pacote"; managedProperty = $props }
    $arquivo = Join-Path $Saida ("loja-{0}.json" -f ($loja.Name -replace '[^\w-]', '_'))
    ($doc | ConvertTo-Json -Depth 10) | Set-Content $arquivo -Encoding utf8NoBOM
    Write-Host ("  loja {0,-8} {1,-30} {2} impressora(s) -> {3}" -f $loja.Name, $loja.Group[0].store_name, @($impressoras).Count, $arquivo)
}
Write-Host "`n$(@($lojas).Count) loja(s) geradas em $Saida" -ForegroundColor Green
