<#
.SYNOPSIS
  Prepara um coletor para a homologação da Fase 1 em um comando.

.DESCRIPTION
  1. Identifica o coletor (modelo, Android, rede)
  2. Instala/atualiza o A.print.Bridge (debug) — mantendo dados existentes. A ferramenta de teste
     da API vai dentro dele, oculta (o app separado PB Cliente Teste não é mais instalado)
  3. Concede a permissão de notificação (Android 13+)
  4. Ativa a licença de laboratório
  5. Abre o A.print.Bridge
  Nada é desinstalado: impressoras e histórico já configurados no coletor são preservados.

.EXAMPLE
  pwsh -File tools\homologacao\instalar-coletor.ps1
#>
param([string]$Serial, [string]$ApkDir, [switch]$SemLicenca)
. "$PSScriptRoot\comum.ps1"

Write-Etapa 'Coletor'
$Serial = Select-Coletor $Serial
$modelo = (Invoke-Adb $Serial shell getprop ro.product.model).Trim()
$fabricante = (Invoke-Adb $Serial shell getprop ro.product.manufacturer).Trim()
$android = (Invoke-Adb $Serial shell getprop ro.build.version.release).Trim()
$sdk = [int](Invoke-Adb $Serial shell getprop ro.build.version.sdk).Trim()
$ip = (Invoke-Adb $Serial shell ip -4 addr show wlan0) -match 'inet ' | ForEach-Object { ($_ -split '\s+')[2] }
Write-Ok "$fabricante $modelo — Android $android (API $sdk) — serial $Serial — Wi-Fi $ip"

Write-Etapa 'Instalação'
# Visto no CF550: na 1ª instalação de um APK fora da Play Store o Google Play Protect abre um
# diálogo no coletor e a instalação fica esperando; sem resposta, ela falha por tempo.
Write-Host '  Se aparecer "Google Play Protect" na tela do coletor, toque em "Não enviar" (é APK interno de teste).' -ForegroundColor Yellow
foreach ($apk in @('a.print.bridge-debug.apk')) {
    $caminho = Find-Apk $ApkDir $apk
    $hash = (Get-FileHash $caminho -Algorithm SHA256).Hash.Substring(0, 16)
    $r = Invoke-Adb $Serial install -r $caminho
    if ($r -match 'Success') { Write-Ok "$(Split-Path $caminho -Leaf) (sha256 $hash…)" }
    else {
        Write-Falha "$(Split-Path $caminho -Leaf): $($r -join ' ')"
        if (-not ($r -match 'Failure')) {
            Write-Host '  Sem resposta de sucesso: provavelmente o diálogo do Google Play Protect ficou sem resposta no coletor.' -ForegroundColor Yellow
            Write-Host '  Toque em "Não enviar" na tela do coletor e rode este script de novo.' -ForegroundColor Yellow
        }
        if ($r -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
            Write-Host '  Já existe uma versão assinada com OUTRA chave. Desinstale-a manualmente (perde os dados dela):' -ForegroundColor Yellow
            Write-Host "  adb -s $Serial uninstall <pacote>" -ForegroundColor Yellow
        }
        exit 1
    }
}
# "adb install -r" preserva o estado "desativado" de um app que alguém desativou no coletor
# (Configurações > Apps > Desativar, ou pm disable-user). O script NÃO reativa sozinho: avisa.
foreach ($pacote in @($script:PacotePrintBridge)) {
    $estado = Invoke-Adb $Serial shell dumpsys package $pacote | Select-String -Pattern 'enabled=(\d)' | Select-Object -First 1
    if ($estado -and $estado.Matches[0].Groups[1].Value -in '2', '3', '4') {
        Write-Falha "$pacote está DESATIVADO neste coletor (alguém o desativou)."
        Write-Host "  Se ninguém estiver usando o coletor para outra coisa, reative com:" -ForegroundColor Yellow
        Write-Host "  adb -s $Serial shell pm enable $pacote" -ForegroundColor Yellow
        exit 1
    }
}

Write-Etapa 'Permissões'
if ($sdk -ge 33) {
    Invoke-Adb $Serial shell pm grant $script:PacotePrintBridge android.permission.POST_NOTIFICATIONS | Out-Null
    Write-Ok 'Notificações concedidas (Android 13+)'
} else {
    Write-Ok "Android $android não exige permissão de notificação"
}

if (-not $SemLicenca) {
    Write-Etapa 'Licença de laboratório'
    & "$PSScriptRoot\ativar-licenca-lab.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Etapa 'Abrindo o A.print.Bridge'
Invoke-Adb $Serial shell monkey -p $script:PacotePrintBridge -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Ok 'Pronto. Próximo passo: pwsh -File tools\homologacao\teste-fumaca-coletor.ps1'
