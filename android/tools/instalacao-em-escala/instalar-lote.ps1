<#
.SYNOPSIS
  Bancada de preparação: instala e configura TODOS os coletores conectados (hub USB) de uma vez.

.DESCRIPTION
  Para cada coletor em "adb devices", em paralelo:
    1. identifica (serial, modelo, Android) e checa se o app foi desativado por alguém;
    2. instala/atualiza o A.print.Bridge (sem desinstalar: dados preservados);
    3. monta a configuração da loja a partir da PLANILHA (linhas do serial; se não houver, linhas
       com serial "*") e aplica pelo mesmo mecanismo do MDM (provisioning.json lido pelo app);
    4. opcional: licença de laboratório (-LicencaLab, só build debug);
    5. devolve o coletor à tela inicial.
  No fim grava um RELATÓRIO CSV (um coletor por linha) — é o registro do lote.

  Em produção, com MDM/EMM, esta bancada é dispensável: o MDM instala o APK e entrega a mesma
  configuração por Managed Configurations (ver INSTALACAO-EM-ESCALA.md). A bancada serve para
  piloto, lojas sem MDM, reposição de aparelho e laboratório.

.PARAMETER Planilha
  CSV: serial,store_code,store_name,printer_name,printer_ip,printer_port,language,model,dpi,paper,is_default
  Uma linha por impressora. serial "*" = configuração para coletores que não aparecem na planilha.

.EXAMPLE
  pwsh -File tools\instalacao-em-escala\instalar-lote.ps1 -Planilha lote-loja-4521.csv
  pwsh -File tools\instalacao-em-escala\instalar-lote.ps1 -Planilha lote.csv -LicencaLab -Paralelo 8
#>
param(
    [string]$Planilha,
    [string]$Apk,
    [int]$Paralelo = 6,
    [switch]$LicencaLab,
    [switch]$ComClienteTeste,
    [string]$Relatorio
)
. "$PSScriptRoot\..\homologacao\comum.ps1"

if (-not $Apk) { $Apk = Find-Apk '' 'a.print.bridge-debug.apk' }
$pacote = if ($Apk -match 'release') { 'br.com.atos.printbridge' } else { $script:PacotePrintBridge }
$cliente = if ($ComClienteTeste) { Find-Apk '' 'pb-cliente-teste-debug.apk' } else { $null }
if (-not $Relatorio) { $Relatorio = Join-Path (Get-Location) ("relatorio-lote-{0:yyyyMMdd-HHmmss}.csv" -f (Get-Date)) }

$linhas = if ($Planilha) { Import-Csv $Planilha } else { @() }
# Licença de laboratório: um token POR coletor (anti-clonagem), gerado com Java após ler o deviceId.
$geradorToken = if ($LicencaLab) { Get-GeradorTokenLab } else { $null }

$adbPath = $script:AdbPath
$coletores = & $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split "`t")[0] }
if (-not $coletores) { throw 'Nenhum coletor conectado/autorizado (adb devices).' }
Write-Etapa "Lote: $(@($coletores).Count) coletor(es), $Paralelo em paralelo — APK $(Split-Path $Apk -Leaf) ($pacote)"
Write-Host '  Na 1ª instalação o Google Play Protect pode perguntar no coletor: toque em "Não enviar".' -ForegroundColor Yellow

$resultados = $coletores | ForEach-Object -ThrottleLimit $Paralelo -Parallel {
    $serial = $_
    $adb = $using:adbPath
    $pacote = $using:pacote
    $inicio = Get-Date
    $r = [ordered]@{ serial = $serial; modelo = ''; android = ''; loja = ''; impressoras = 0; instalacao = ''; configuracao = ''; licenca = ''; status = 'FALHOU'; segundos = 0 }
    function A { & $adb -s $serial @args 2>&1 }
    try {
        $r.modelo = "$(A shell getprop ro.product.manufacturer) $(A shell getprop ro.product.model)".Trim()
        $r.android = "$(A shell getprop ro.build.version.release)".Trim()

        $estado = A shell dumpsys package $pacote | Select-String -Pattern 'enabled=(\d)' | Select-Object -First 1
        if ($estado -and $estado.Matches[0].Groups[1].Value -in '2', '3', '4') { throw "app DESATIVADO no coletor (adb shell pm enable $pacote)" }

        $inst = A install -r $using:Apk
        if (-not ($inst -match 'Success')) { throw "instalação: $(($inst | Select-Object -Last 1)) (Play Protect sem resposta?)" }
        if ($using:cliente) { A install -r $using:cliente | Out-Null }
        $r.instalacao = 'OK'

        # Configuração da loja: linhas do próprio serial; senão, as do serial "*".
        $minhas = @($using:linhas | Where-Object { $_.serial -eq $serial })
        if (-not $minhas) { $minhas = @($using:linhas | Where-Object { $_.serial -eq '*' }) }
        if ($minhas) {
            $cfg = [ordered]@{
                store_code = $minhas[0].store_code
                store_name = $minhas[0].store_name
                printers   = @($minhas | Where-Object { $_.printer_ip } | ForEach-Object {
                        [ordered]@{
                            name = $_.printer_name; ip = $_.printer_ip; port = $(if ($_.printer_port) { [int]$_.printer_port } else { 9100 })
                            language = $_.language; model = $_.model; dpi = $(if ($_.dpi) { [int]$_.dpi } else { 203 }); paper = $_.paper
                            is_default = ($_.is_default -match '^(true|1|sim|s)$')
                        }
                    })
            }
            $r.loja = $cfg.store_code
            $r.impressoras = $cfg.printers.Count
            $json = Join-Path ([IO.Path]::GetTempPath()) "provisioning-$serial.json"
            ($cfg | ConvertTo-Json -Depth 5) | Set-Content $json -Encoding utf8NoBOM
            $pasta = "/sdcard/Android/data/$pacote/files"
            A shell "mkdir -p $pasta; rm -f $pasta/provisioning-result.txt" | Out-Null
            $push = A push $json "$pasta/provisioning.json"
            Remove-Item $json -ErrorAction SilentlyContinue
            if ($LASTEXITCODE -ne 0) { throw "push da configuração: $push" }
            # Abrir o app aplica o arquivo (e tira o estado "stopped" de app recém-instalado).
            A shell am force-stop $pacote | Out-Null
            A shell am start -n "$pacote/br.com.atos.printbridge.MainActivity" | Out-Null
            $resposta = $null
            $limite = (Get-Date).AddSeconds(25)
            do {
                Start-Sleep -Seconds 1
                $resposta = "$(A shell cat $pasta/provisioning-result.txt)".Trim()
                if ($resposta -match 'No such file') { $resposta = $null }
            } until ($resposta -or (Get-Date) -gt $limite)
            $r.configuracao = if ($resposta) { $resposta } else { 'SEM RESPOSTA do app em 25s' }
            if (-not ($resposta -match '^OK')) { throw "configuração: $($r.configuracao)" }
        } else {
            $r.configuracao = 'sem linha na planilha (MDM configura depois)'
        }

        if ($using:geradorToken) {
            # 1ª chamada sem token: o app devolve o deviceId no logcat; o token é assinado para ele.
            A logcat -c | Out-Null
            A shell am start -n "$pacote/br.com.atos.printbridge.debug.LabProvisioningActivity" | Out-Null
            $deviceId = $null
            $limite = (Get-Date).AddSeconds(15)
            do {
                Start-Sleep -Milliseconds 700
                $linha = A logcat -d -s 'PrintBridge:I' | Select-String 'lab_license_provisioned' | Select-Object -Last 1
                if ($linha -and "$linha" -match '"deviceId":"([0-9a-f]+)"') { $deviceId = $Matches[1] }
            } until ($deviceId -or (Get-Date) -gt $limite)
            if (-not $deviceId) { throw 'licença de laboratório: deviceId não lido do app' }
            $token = (& java $using:geradorToken 30 $deviceId).Trim()

            A logcat -c | Out-Null
            A shell am start -n "$pacote/br.com.atos.printbridge.debug.LabProvisioningActivity" --es token $token | Out-Null
            $lic = $null
            $limite = (Get-Date).AddSeconds(15)
            do {
                Start-Sleep -Milliseconds 700
                $linha = A logcat -d -s 'PrintBridge:I' | Select-String 'lab_license_provisioned' | Select-Object -Last 1
                if ($linha -and "$linha" -match '"status":"(\w+)"') { $lic = $Matches[1] }
            } until ($lic -or (Get-Date) -gt $limite)
            $r.licenca = if ($lic) { $lic } else { 'sem resposta' }
            if ($lic -ne 'OPERATING') { throw "licença de laboratório: $($r.licenca)" }
        }

        A shell input keyevent KEYCODE_HOME | Out-Null
        $r.status = 'OK'
    } catch {
        $r.status = "FALHOU: $($_.Exception.Message)"
    }
    $r.segundos = [int]((Get-Date) - $inicio).TotalSeconds
    [pscustomobject]$r
}

$resultados | Sort-Object serial | Export-Csv $Relatorio -NoTypeInformation -Encoding utf8
Write-Etapa 'Resultado do lote'
foreach ($x in $resultados | Sort-Object serial) {
    $linha = "{0,-18} {1,-18} loja {2,-6} impressoras {3}  {4}  ({5}s)" -f $x.serial, $x.modelo, $x.loja, $x.impressoras, $x.status, $x.segundos
    if ($x.status -eq 'OK') { Write-Ok $linha } else { Write-Falha $linha }
}
$ok = @($resultados | Where-Object status -eq 'OK').Count
Write-Host "`n  $ok de $(@($resultados).Count) coletores prontos. Relatório: $Relatorio" -ForegroundColor $(if ($ok -eq @($resultados).Count) { 'Green' } else { 'Yellow' })
if ($ok -ne @($resultados).Count) { exit 1 }
