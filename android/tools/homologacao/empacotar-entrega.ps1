<#
.SYNOPSIS
  Gera o pacote de homologação da Fase 1 (APKs + scripts + roteiro + SHA-256) numa pasta só.

.DESCRIPTION
  Compila (testes JVM inclusos), copia os APKs debug com nomes estáveis, gera um token de
  licença de laboratório (90 dias) e monta:

    Entrega-Fase1-<data>\
      APK\a.print.bridge-debug.apk (com a ferramenta oculta de teste da API), SHA256SUMS.txt
      tools\homologacao\*.ps1, token-lab.txt      tools\licenca\GerarTokenTeste.java
      ROTEIRO-TESTE-FASE1.md, FASE1-PRINT-BRIDGE-CORE.md, INTEGRATION.md
      INSTALAR.bat, TESTE-FUMACA.bat, IMPRESSORA-SIMULADA.bat

  O pacote é artefato gerado — fica fora do git (.gitignore).

.EXAMPLE
  pwsh -File tools\homologacao\empacotar-entrega.ps1
  pwsh -File tools\homologacao\empacotar-entrega.ps1 -Cliente assai -SemTestes
#>
param([string]$Destino, [switch]$SemTestes, [ValidateSet('carrefour', 'roldao', 'assai', 'generico')][string]$Cliente = 'carrefour')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Destino) { $Destino = Join-Path $repo ("Entrega-Fase1-{0}-{1:yyyy-MM-dd}" -f $Cliente, (Get-Date)) }

# Flavor do cliente: carrefour -> Carrefour (tarefas :app:assembleCarrefourDebug etc.)
$variante = $Cliente.Substring(0, 1).ToUpper() + $Cliente.Substring(1)
Write-Host "== Build ($Cliente)" -ForegroundColor Cyan
$tarefas = @(":app:assemble${variante}Debug")
if (-not $SemTestes) { $tarefas = @(":app:test${variante}DebugUnitTest") + $tarefas }
Push-Location $repo
try {
    & .\gradlew.bat @tarefas --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'Build/testes falharam — pacote NÃO gerado.' }
} finally { Pop-Location }

Write-Host "== Montando $Destino" -ForegroundColor Cyan
if (Test-Path $Destino) { Remove-Item $Destino -Recurse -Force }
$apkDir = New-Item -ItemType Directory -Force -Path (Join-Path $Destino 'APK')
$toolsDir = New-Item -ItemType Directory -Force -Path (Join-Path $Destino 'tools\homologacao')
$licDir = New-Item -ItemType Directory -Force -Path (Join-Path $Destino 'tools\licenca')

Copy-Item (Join-Path $repo "app\build\outputs\apk\$Cliente\debug\app-$Cliente-debug.apk") (Join-Path $apkDir 'a.print.bridge-debug.apk')
Get-ChildItem $apkDir -Filter *.apk | ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name } |
    Set-Content (Join-Path $apkDir 'SHA256SUMS.txt') -Encoding utf8

Get-ChildItem $PSScriptRoot -Filter *.ps1 | Copy-Item -Destination $toolsDir
Copy-Item (Join-Path $repo 'tools\licenca\GerarTokenTeste.java') $licDir
$escalaDir = New-Item -ItemType Directory -Force -Path (Join-Path $Destino 'tools\instalacao-em-escala')
Get-ChildItem (Join-Path $repo 'tools\instalacao-em-escala') -File | Copy-Item -Destination $escalaDir
# Sem token-lab.txt: a licença é por aparelho (anti-clonagem), gerada na ativação com Java.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Warning 'Java não encontrado nesta máquina: quem for ativar a licença de laboratório vai precisar de Java 17+.'
}
foreach ($doc in 'ROTEIRO-TESTE-FASE1.md', 'FASE1-PRINT-BRIDGE-CORE.md', 'INTEGRATION.md', 'INSTALACAO-EM-ESCALA.md') {
    Copy-Item (Join-Path $repo "docs\entrega\$doc") $Destino
}

# Atalhos de duplo clique (chamam o pwsh; se só houver Windows PowerShell 5.1, usam ele).
$bat = @'
@echo off
cd /d "%~dp0"
where pwsh >nul 2>nul && (set PS=pwsh) || (set PS=powershell)
%PS% -NoProfile -ExecutionPolicy Bypass -File tools\homologacao\{0} %*
pause
'@
@{ 'INSTALAR.bat' = 'instalar-coletor.ps1'; 'TESTE-FUMACA.bat' = 'teste-fumaca-coletor.ps1 -TestarOffline'; 'IMPRESSORA-SIMULADA.bat' = 'impressora-simulada.ps1' }.GetEnumerator() |
    ForEach-Object { ($bat -replace '\{0\}', $_.Value) | Set-Content (Join-Path $Destino $_.Key) -Encoding ascii }

Write-Host "`n== Pacote pronto: $Destino" -ForegroundColor Green
Get-Content (Join-Path $apkDir 'SHA256SUMS.txt')
