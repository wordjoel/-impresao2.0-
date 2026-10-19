# Funções compartilhadas pelos scripts de homologação da Fase 1 (dot-source: . "$PSScriptRoot\comum.ps1").

$ErrorActionPreference = 'Stop'
# adb devolve UTF-8: sem isto "página", "✗", "⇐" chegam corrompidos e as verificações não casam.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$script:PacotePrintBridge = 'br.com.atos.printbridge.debug'
# Ferramenta OCULTA de teste da API, dentro do próprio A.print.Bridge (substituiu o app PB Cliente Teste).
$script:AtividadeTeste = "$script:PacotePrintBridge/br.com.atos.printbridge.diagnostics.ApiTestToolActivity"

function Get-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk', (Join-Path $PSScriptRoot '..\ADB'))) {
        if ($root) {
            foreach ($candidate in @((Join-Path $root 'platform-tools\adb.exe'), (Join-Path $root 'adb.exe'))) {
                if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
            }
        }
    }
    throw 'adb não encontrado. Instale o Android Platform-Tools ou coloque adb.exe na pasta ADB do pacote.'
}

$script:AdbPath = Get-Adb

function Select-Coletor([string]$Serial) {
    $devices = & $script:AdbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split "`t")[0] }
    if ($Serial) {
        if ($devices -notcontains $Serial) { throw "Coletor $Serial não está conectado/autorizado (adb devices)." }
        return $Serial
    }
    if (-not $devices) { throw 'Nenhum coletor conectado. Ligue o cabo USB, ative a Depuração USB e autorize este PC.' }
    if (@($devices).Count -gt 1) { throw "Mais de um coletor conectado ($($devices -join ', ')). Use -Serial." }
    return @($devices)[0]
}

# Sem parâmetros declarados de propósito: flags do adb (-a, -n, -s...) não podem ser
# confundidas com parâmetros do PowerShell. Uso: Invoke-Adb <serial> <args do adb...>
function Invoke-Adb {
    $serial, $rest = $args
    & $script:AdbPath -s $serial @rest
}

# A licença só vale no aparelho cujo deviceId está assinado nela (anti-clonagem): o token de
# laboratório é gerado POR COLETOR. O deviceId sai do evento lab_license_provisioned do app.
function Get-LabDeviceId([string]$Serial, [string]$Pacote = $script:PacotePrintBridge) {
    Invoke-Adb $Serial logcat -c
    Invoke-Adb $Serial shell am start -n "$Pacote/br.com.atos.printbridge.debug.LabProvisioningActivity" 2>&1 | Out-Null
    $limite = (Get-Date).AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 500
        $linha = Invoke-Adb $Serial logcat -d -s 'PrintBridge:I' | Select-String 'lab_license_provisioned' | Select-Object -Last 1
        if ($linha -and "$linha" -match '"deviceId":"([0-9a-f]+)"') { return $Matches[1] }
    } until ((Get-Date) -gt $limite)
    throw "Não foi possível ler o deviceId do coletor $Serial (app instalado é o build debug?)."
}

function Get-GeradorTokenLab {
    $gerador = @((Join-Path $PSScriptRoot '..\licenca\GerarTokenTeste.java'), (Join-Path $PSScriptRoot '..\..\tools\licenca\GerarTokenTeste.java')) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not $gerador) {
        throw 'Gerar a licença de laboratório exige Java 17+ (o token é por coletor). Ou informe -Token gerado para este deviceId.'
    }
    return (Resolve-Path $gerador).Path
}

function New-LabToken([string]$DeviceId, [int]$Dias = 30) {
    return (& java (Get-GeradorTokenLab) $Dias $DeviceId).Trim()
}

function Write-Etapa([string]$texto) { Write-Host "`n== $texto" -ForegroundColor Cyan }
function Write-Ok([string]$texto) { Write-Host "  [OK] $texto" -ForegroundColor Green }
function Write-Falha([string]$texto) { Write-Host "  [FALHA] $texto" -ForegroundColor Red }

function Find-Apk([string]$ApkDir, [string]$nome) {
    $candidatos = @()
    if ($ApkDir) { $candidatos += Join-Path $ApkDir $nome }
    $candidatos += (Join-Path $PSScriptRoot "..\..\APK\$nome")                       # pacote de entrega (raiz\APK)
    $repo = Resolve-Path (Join-Path $PSScriptRoot '..\..') -ErrorAction SilentlyContinue  # repositório
    # Uma versão por cliente (flavor). Padrão carrefour; outro cliente: $env:PB_CLIENTE = 'assai'.
    $cliente = if ($env:PB_CLIENTE) { $env:PB_CLIENTE } else { 'carrefour' }
    if ($repo) {
        $candidatos += Join-Path $repo "app\build\outputs\apk\$cliente\debug\app-$cliente-debug.apk"
        $candidatos += Join-Path $repo 'sample-client\build\outputs\apk\debug\sample-client-debug.apk'
    }
    $map = @{ 'a.print.bridge-debug.apk' = "app-$cliente-debug.apk"; 'pb-cliente-teste-debug.apk' = 'sample-client-debug.apk' }
    foreach ($c in $candidatos) {
        if ((Test-Path $c) -and ((Split-Path $c -Leaf) -in @($nome, $map[$nome]))) { return (Resolve-Path $c).Path }
    }
    throw "APK $nome não encontrado (procurado em: $($candidatos -join '; '))."
}
