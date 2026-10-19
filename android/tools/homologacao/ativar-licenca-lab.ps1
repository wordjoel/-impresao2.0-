<#
.SYNOPSIS
  Ativa a licença de LABORATÓRIO no A.print.Bridge (build debug) por ADB, sem digitar token.

.DESCRIPTION
  Usa o token de teste (assinado com a chave de TESTE publicada na documentação — não vale em
  produção). Sem -Token, lê o deviceId do coletor e gera com Java um token SÓ para ele: a licença
  não vale em outro aparelho (anti-clonagem), por isso não existe mais um token-lab.txt único.
  Só funciona no build DEBUG (o receptor de provisionamento não existe no release).

.EXAMPLE
  pwsh -File tools\homologacao\ativar-licenca-lab.ps1
  pwsh -File tools\homologacao\ativar-licenca-lab.ps1 -Dias 90 -Serial CF550A4LAWBB340
#>
param([string]$Serial, [string]$Token, [int]$Dias = 30)
. "$PSScriptRoot\comum.ps1"

$Serial = Select-Coletor $Serial
if (-not $Token) {
    $deviceId = Get-LabDeviceId $Serial
    $Token = New-LabToken $deviceId $Dias
    Write-Host "Token de laboratório gerado para o deviceId $($deviceId.Substring(0, 12))… ($Dias dias)"
}

# Activity invisível só do build debug (LabProvisioningActivity, protegida por DUMP = shell do ADB).
# O resultado sai no logcat, evento lab_license_provisioned. Sem -W: a Activity fecha no onCreate
# sem desenhar nada, e "am start -W" ficaria esperando o primeiro frame para sempre.
Invoke-Adb $Serial logcat -c
Invoke-Adb $Serial shell am start -n "$script:PacotePrintBridge/br.com.atos.printbridge.debug.LabProvisioningActivity" --es token $Token 2>&1 | Out-Null
$status = $null
$limite = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Milliseconds 500
    $linha = Invoke-Adb $Serial logcat -d -s 'PrintBridge:I' | Select-String 'lab_license_provisioned' | Select-Object -Last 1
    if ($linha -and "$linha" -match '"status":"(\w+)"') { $status = $Matches[1] }
} until ($status -or (Get-Date) -gt $limite)

if ($status -eq 'OPERATING') {
    Write-Ok "Licença ativa (OPERATING) no coletor $Serial"
} else {
    Write-Falha "Licença NÃO ativada (status: $(if ($status) { $status } else { 'sem resposta do app' }))."
    exit 1
}
