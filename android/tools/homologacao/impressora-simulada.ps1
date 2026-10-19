<#
.SYNOPSIS
  Impressora térmica SIMULADA (TCP 9100) para testar o A.print.Bridge sem TSC física.

.DESCRIPTION
  Escuta na porta (padrão 9100, a mesma RAW/JetDirect da TSC), recebe cada job exatamente como a
  impressora receberia e grava em .\recebidos:
    - job-NNN.zpl / .tspl / .bin  -> bytes brutos recebidos (prova byte a byte)
    - job-NNN.png                 -> render do gráfico ^GFA (ZPL) ou BITMAP (TSPL) do PDF/imagem
  Etiqueta ZPL de TEXTO (template SIF) não é renderizada aqui (exigiria um interpretador ZPL):
  o .zpl é o que vale para conferência; para visualizar, cole em um visualizador ZPL de sua
  confiança — NÃO envie dados reais de produção a serviços externos.

  Para simular "impressora desligada", pare o script (Ctrl+C): o Print Bridge deve mostrar
  "Tentando novamente (n/5)" e reenviar sozinho quando o script voltar.

.EXAMPLE
  pwsh -File tools\homologacao\impressora-simulada.ps1
  pwsh -File tools\homologacao\impressora-simulada.ps1 -Porta 9101 -Pasta C:\temp\jobs
#>
param(
    [int]$Porta = 9100,
    [string]$Pasta = (Join-Path $PSScriptRoot 'recebidos'),
    # Simula impressora lenta/instável: recusa as N primeiras conexões (teste de retry).
    [int]$RecusarPrimeiras = 0
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Pasta | Out-Null
Add-Type -AssemblyName System.Drawing

# Grava 1 bit por ponto direto num PNG 1bpp (cópia de memória, sem SetPixel: ~ms por página).
# $blackBit = valor do bit que significa "ponto queimado" (ZPL ^GFA = 1, TSPL BITMAP = 0).
function Save-Png([byte[]]$data, [int]$bytesPerRow, [int]$height, [int]$blackBit, [string]$file) {
    $bmp = [System.Drawing.Bitmap]::new($bytesPerRow * 8, $height, [System.Drawing.Imaging.PixelFormat]::Format1bppIndexed)
    # Paleta padrão do 1bpp: índice 0 = preto, 1 = branco. Bit 1 do ZPL (preto) precisa virar 0.
    $rows = if ($blackBit -eq 1) { [byte[]]($data | ForEach-Object { $_ -bxor 0xFF }) } else { $data }
    $rect = [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $height)
    $locked = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, $bmp.PixelFormat)
    for ($y = 0; $y -lt $height; $y++) {
        [System.Runtime.InteropServices.Marshal]::Copy($rows, $y * $bytesPerRow, [IntPtr]::Add($locked.Scan0, $y * $locked.Stride), $bytesPerRow)
    }
    $bmp.UnlockBits($locked)
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

function Render-Zpl([string]$text, [string]$png) {
    # ^GFA,<total>,<total>,<bytesPorLinha>,<HEX>  (o que o Document Rasterizer gera)
    $m = [regex]::Match($text, '\^GFA,(\d+),(\d+),(\d+),([0-9A-Fa-f]+)')
    if (-not $m.Success) { return $false }
    $bpr = [int]$m.Groups[3].Value
    $data = [Convert]::FromHexString($m.Groups[4].Value)
    Save-Png $data $bpr ([int]($data.Length / $bpr)) 1 $png
    return $true
}

function Render-Tspl([byte[]]$bytes, [string]$png) {
    # BITMAP x,y,larguraBytes,altura,modo,<dados binários>   (bit 0 = ponto queimado na TSC)
    $latin = [System.Text.Encoding]::Latin1.GetString($bytes)
    $m = [regex]::Match($latin, 'BITMAP (\d+),(\d+),(\d+),(\d+),(\d+),')
    if (-not $m.Success) { return $false }
    $bpr = [int]$m.Groups[3].Value; $h = [int]$m.Groups[4].Value
    $start = $m.Index + $m.Length
    $data = [byte[]]$bytes[$start..($start + $bpr * $h - 1)]
    Save-Png $data $bpr $h 0 $png
    return $true
}

$ips = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' }).IPAddress -join ', '
if (-not (Get-NetFirewallRule -DisplayName "PrintBridge impressora simulada $Porta" -ErrorAction SilentlyContinue)) {
    Write-Warning "Sem regra de firewall para a porta $Porta. Se o coletor não conectar, rode UMA VEZ como administrador:"
    Write-Warning "  New-NetFirewallRule -DisplayName 'PrintBridge impressora simulada $Porta' -Direction Inbound -Protocol TCP -LocalPort $Porta -Action Allow -Profile Any"
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Porta)
$listener.Start()
Write-Host "Impressora simulada ouvindo em TCP $Porta  (IPs deste PC: $ips)" -ForegroundColor Green
Write-Host "Jobs em: $Pasta   |   Ctrl+C = 'desligar a impressora'" -ForegroundColor Green

$n = (Get-ChildItem $Pasta -Filter 'job-*' -ErrorAction SilentlyContinue | Measure-Object).Count
$recusadas = 0
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $from = $client.Client.RemoteEndPoint
        if ($recusadas -lt $RecusarPrimeiras) {
            $recusadas++
            $client.Close()
            Write-Host ("{0:HH:mm:ss} conexão de {1} RECUSADA de propósito ({2}/{3})" -f (Get-Date), $from, $recusadas, $RecusarPrimeiras) -ForegroundColor Yellow
            continue
        }
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $ms = [System.IO.MemoryStream]::new()
        $client.GetStream().CopyTo($ms)
        $client.Close()
        $bytes = $ms.ToArray()
        $n++
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        $kind = if ($text -match '\^XA') { 'zpl' } elseif ($text -match '^(SIZE|CLS|TEXT|BITMAP)') { 'tspl' } else { 'bin' }
        $base = Join-Path $Pasta ('job-{0:000}' -f $n)
        [System.IO.File]::WriteAllBytes("$base.$kind", $bytes)
        $rendered = switch ($kind) { 'zpl' { Render-Zpl $text "$base.png" } 'tspl' { Render-Tspl $bytes "$base.png" } default { $false } }
        $what = if ($rendered) { 'gráfico -> ' + (Split-Path "$base.png" -Leaf) } elseif ($kind -eq 'zpl') { 'ZPL texto (template/bruto)' } elseif ($bytes.Length -eq 0) { 'conexão VAZIA (típico do túnel adb reverse — use Wi-Fi)' } else { $kind.ToUpper() }
        Write-Host ("{0:HH:mm:ss} job {1:000} de {2}: {3} bytes, {4}  [{5} ms]" -f (Get-Date), $n, $from, $bytes.Length, $what, $sw.ElapsedMilliseconds)
    }
}
finally {
    $listener.Stop()
    Write-Host 'Impressora simulada DESLIGADA.' -ForegroundColor Yellow
}
