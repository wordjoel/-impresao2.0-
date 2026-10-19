<#
.SYNOPSIS
  Teste de fumaça da Fase 1 no coletor, em um comando, com veredito PASSOU/FALHOU.

.DESCRIPTION
  Usa a ferramenta oculta de teste da API (dentro do A.print.Bridge, pela API AIDL) para mandar: etiqueta
  SIF (template), PDF 1 e 3 páginas, imagem, ZPL/TSPL bruto e um pedido inválido. Confere o jobId,
  os callbacks (job-completed, page-x-of-n, document-finished) e a recusa com código estável.

  Destino:
    -Ip local (padrão)  -> "impressora" simulada NO PRÓPRIO COLETOR (nc em 127.0.0.1:9105). Não
                           precisa de TSC nem de firewall no PC. Confere também os bytes recebidos.
    -Ip <IP da TSC>     -> impressão REAL. Confira as etiquetas na mão (SENT ≠ impresso).

  -TestarOffline acrescenta o critério 4/5: impressora desligada → retries 2-4-8s → "liga" →
  entregue sozinho (só no modo local).

.EXAMPLE
  pwsh -File tools\homologacao\teste-fumaca-coletor.ps1
  pwsh -File tools\homologacao\teste-fumaca-coletor.ps1 -TestarOffline
  pwsh -File tools\homologacao\teste-fumaca-coletor.ps1 -Ip 192.168.70.50 -Protocolo ZPL -Dpi 203 -Papel 100x72
#>
param(
    [string]$Serial,
    [string]$Ip = 'local',
    [int]$Porta = 9100,
    [ValidateSet('ZPL', 'TSPL')][string]$Protocolo = 'ZPL',
    [ValidateSet('203', '300')][string]$Dpi = '203',
    [string]$Papel = '100x72',
    [string]$Acoes = 'registrar,sif,pdf1,pdf3,imagem,raw,erro,status',
    [switch]$TestarOffline
)
. "$PSScriptRoot\comum.ps1"

$Serial = Select-Coletor $Serial
$local = $Ip -eq 'local'
# A etiqueta SIF é ZPL: numa impressora TSPL ela é (corretamente) recusada com LANGUAGE_MISMATCH.
if ($Protocolo -eq 'TSPL' -and -not $PSBoundParameters.ContainsKey('Acoes')) { $Acoes = ($Acoes -split ',' | Where-Object { $_ -ne 'sif' }) -join ',' }
$portaLocal = 9105
$arquivoLocal = '/data/local/tmp/pb-fumaca.bin'
$falhas = [System.Collections.Generic.List[string]]::new()

function Start-ImpressoraNoColetor([switch]$Limpar) {
    Stop-ImpressoraNoColetor
    if ($Limpar) { Invoke-Adb $Serial shell "rm -f $arquivoLocal" | Out-Null }
    # nc em modo servidor (-L): continua escutando e trata cada conexão num processo filho, como
    # uma impressora de verdade. (Um "nc -l" por job perdia a conexão seguinte que o kernel já
    # tinha aceitado na fila enquanto o nc anterior encerrava.)
    Invoke-Adb $Serial shell "nohup nc -L -p $portaLocal sh -c 'cat >> $arquivoLocal' >/dev/null 2>&1 &" | Out-Null
    Start-Sleep -Milliseconds 800
}
function Stop-ImpressoraNoColetor {
    # -x casa o NOME do processo ("nc"), nunca a linha de comando do shell que chamou o pkill.
    Invoke-Adb $Serial shell 'pkill -x nc' 2>&1 | Out-Null
    Start-Sleep -Milliseconds 300
}
function Get-RecebidoNoColetor {
    # Cada etiqueta ZPL termina em ^XZ; cada job TSPL em PRINT 1.
    $fim = if ($Protocolo -eq 'TSPL') { 'PRINT 1' } else { 'XZ' }
    $jobs = Invoke-Adb $Serial shell "grep -o '$fim' $arquivoLocal 2>/dev/null | wc -l"
    $bytes = Invoke-Adb $Serial shell "wc -c < $arquivoLocal 2>/dev/null || echo 0"
    $gfa = Invoke-Adb $Serial shell "grep -o 'GFA,' $arquivoLocal 2>/dev/null | wc -l"
    # O BITMAP TSPL é binário: -a para o grep não tratar o arquivo como "binário, sem linhas".
    $bitmap = Invoke-Adb $Serial shell "grep -ao 'BITMAP 0,0,' $arquivoLocal 2>/dev/null | wc -l"
    $size = "SIZE $($Papel -replace 'x', ' mm,') mm"
    $papelTspl = Invoke-Adb $Serial shell "grep -ao '$size' $arquivoLocal 2>/dev/null | wc -l"
    return @{ Jobs = [int]("$jobs".Trim()); Bytes = [int]("$bytes".Trim()); Gfa = [int]("$gfa".Trim())
        Bitmap = [int]("$bitmap".Trim()); PapelTspl = [int]("$papelTspl".Trim()) }
}

function Invoke-Roteiro([string]$acoes, [string]$ip, [int]$porta, [int]$esperaSeg) {
    Invoke-Adb $Serial logcat -c
    # execucao = id único: a ferramenta nunca repete um roteiro (o cliente antigo chegou a repetir).
    Invoke-Adb $Serial shell am start -n $script:AtividadeTeste --es execucao ([guid]::NewGuid().ToString()) --es ip $ip --es porta $porta `
        --es protocolo $Protocolo --es dpi $Dpi --es papel $Papel --es acao $acoes 2>&1 | Out-Null
    $fim = (Get-Date).AddSeconds($esperaSeg)
    do {
        Start-Sleep -Seconds 2
        $log = Invoke-Adb $Serial logcat -d -s 'PBClienteTeste:I' | ForEach-Object { ($_ -split 'PBClienteTeste: ', 2)[1] } | Where-Object { $_ }
    } until (($log -match '=== FIM DO ROTEIRO ===') -or (Get-Date) -gt $fim)
    Start-Sleep -Seconds 3 # callbacks atrasados
    return Invoke-Adb $Serial logcat -d -s 'PBClienteTeste:I' | ForEach-Object { ($_ -split 'PBClienteTeste: ', 2)[1] } | Where-Object { $_ }
}

Write-Etapa "Teste de fumaça — coletor $Serial — destino $(if ($local) { "impressora simulada no coletor (127.0.0.1:$portaLocal)" } else { "$Ip`:$Porta (REAL)" }) $Protocolo $Dpi dpi $Papel"
if ($local) { Start-ImpressoraNoColetor -Limpar }
$destinoIp = if ($local) { '127.0.0.1' } else { $Ip }
$destinoPorta = if ($local) { $portaLocal } else { $Porta }

$log = Invoke-Roteiro $Acoes $destinoIp $destinoPorta 120
$log | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

Write-Etapa 'Veredito'
if (-not ($log -match '^Conectado')) { $falhas.Add('Ferramenta de teste não conectou na API do Print Bridge (instalado? licença?)') }
$enviados = @($log | Select-String -Pattern 'OK jobId=([0-9a-f-]+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
$acoesImpressao = @($Acoes.Split(',') | Where-Object { $_ -in 'sif', 'pdf1', 'pdf3', 'imagem', 'raw' })
if ($enviados.Count -eq $acoesImpressao.Count) { Write-Ok "$($enviados.Count) pedidos aceitos e persistidos (jobId devolvido)" }
else { $falhas.Add("esperados $($acoesImpressao.Count) jobIds, recebidos $($enviados.Count)") }

$paginas = $acoesImpressao | ForEach-Object { switch ($_) { 'pdf3' { 3 } default { 1 } } } | Measure-Object -Sum | Select-Object -ExpandProperty Sum
$concluidos = @($log -match 'job-completed').Count
if ($concluidos -eq $paginas) { Write-Ok "$concluidos/$paginas páginas com callback job-completed" }
else { $falhas.Add("callbacks job-completed: $concluidos de $paginas") }
if ($acoesImpressao -contains 'pdf3') {
    if (($log -match 'page-x-of-n-printed página 3/3') -and ($log -match 'document-finished 3/3')) { Write-Ok 'PDF 3 páginas: página 1/3→3/3 em ordem + document-finished 3/3' }
    else { $falhas.Add('PDF multipágina sem page-x-of-n 3/3 ou document-finished 3/3') }
}
# O pedido inválido usa o template SIF (ZPL). Numa impressora TSPL a checagem de linguagem vem antes
# da de variáveis, então ali a recusa correta é LANGUAGE_MISMATCH.
$recusaEsperada = if ($Protocolo -eq 'TSPL') { 'LANGUAGE_MISMATCH' } else { 'MISSING_VARIABLES' }
if ($Acoes -match 'erro') {
    if ($log -match $recusaEsperada) { Write-Ok "Pedido inválido recusado com código $recusaEsperada (nada impresso)" }
    else { $falhas.Add("pedido inválido não foi recusado com $recusaEsperada") }
}
$inesperados = @($log -match '✗' | Where-Object { $_ -notmatch $recusaEsperada })
if ($inesperados) { $falhas.Add("erros inesperados: $($inesperados -join ' | ')") }

if ($local) {
    # O callback chega quando o socket aceitou os bytes; a gravação no arquivo pode vir logo depois.
    $limite = (Get-Date).AddSeconds(10)
    do { $r = Get-RecebidoNoColetor; if ($r.Jobs -ge $paginas) { break }; Start-Sleep -Seconds 1 } while ((Get-Date) -lt $limite)
    $graficos = if ($Protocolo -eq 'TSPL') { "$($r.Bitmap) gráficos BITMAP" } else { "$($r.Gfa) gráficos ^GFA" }
    if ($r.Jobs -ge $paginas) { Write-Ok "Impressora simulada recebeu $($r.Jobs) jobs ($($r.Bytes) bytes, $graficos)" }
    else { $falhas.Add("impressora simulada recebeu $($r.Jobs) de $paginas jobs") }
    if ($Protocolo -eq 'ZPL' -and $r.Gfa -lt 1) { $falhas.Add('nenhum ^GFA (PDF/imagem rasterizado) recebido') }
    if ($Protocolo -eq 'TSPL') {
        if ($r.Bitmap -lt 1) { $falhas.Add('nenhum BITMAP TSPL (PDF/imagem rasterizado) recebido') }
        elseif ($r.PapelTspl -lt $r.Bitmap) { $falhas.Add("BITMAP sem o SIZE do papel $Papel ($($r.PapelTspl) de $($r.Bitmap))") }
        else { Write-Ok "TSPL: $($r.Bitmap) páginas BITMAP com o SIZE do papel $Papel" }
    }
}

if ($TestarOffline -and $local) {
    Write-Etapa 'Offline → volta (critérios 4 e 5)'
    Stop-ImpressoraNoColetor
    Invoke-Adb $Serial logcat -c
    # A SIF é ZPL: numa impressora TSPL ela é recusada (LANGUAGE_MISMATCH) e nunca entra na fila.
    $acaoOffline = if ($Protocolo -eq 'TSPL') { 'raw' } else { 'sif' }
    Invoke-Adb $Serial shell am start -n $script:AtividadeTeste --es execucao ([guid]::NewGuid().ToString()) --es protocolo $Protocolo --es acao $acaoOffline 2>&1 | Out-Null
    Start-Sleep -Seconds 9 # tentativas 1 e 2 falham (2s + 4s)
    $antes = @(Invoke-Adb $Serial logcat -d -s 'PBClienteTeste:I' | Select-String 'job-retrying').Count
    Start-ImpressoraNoColetor
    Start-Sleep -Seconds 14
    $depois = Invoke-Adb $Serial logcat -d -s 'PBClienteTeste:I' | ForEach-Object { ($_ -split 'PBClienteTeste: ', 2)[1] } | Where-Object { $_ }
    $depois | Where-Object { $_ -match '⇐' } | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    if ($antes -ge 2 -and ($depois -match 'job-completed')) { Write-Ok "Impressora desligada: $antes retries, job mantido na fila e entregue sozinho quando voltou" }
    else { $falhas.Add("offline→volta: retries=$antes, completed=$([bool]($depois -match 'job-completed'))") }
}
if ($local) { Stop-ImpressoraNoColetor }

Write-Host ''
if ($falhas.Count -eq 0) {
    Write-Host '  ██ PASSOU ██  Fase 1 funcionando ponta a ponta neste coletor.' -ForegroundColor Green
    if (-not $local) { Write-Host '  Confira as etiquetas impressas na mão: SENT só prova que a impressora aceitou os bytes.' -ForegroundColor Yellow }
    exit 0
} else {
    Write-Host '  ██ FALHOU ██' -ForegroundColor Red
    $falhas | ForEach-Object { Write-Falha $_ }
    exit 1
}
