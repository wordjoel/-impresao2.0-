<#
.SYNOPSIS
  Ponte de TESTE: impressora USB no notebook ↔ TCP 9100, para o coletor imprimir nela como numa TSC de rede.

.DESCRIPTION
  Para quando a TSC e o coletor não estão na mesma rede (ex.: Wi-Fi de visitantes isolado da rede
  interna). NÃO substitui o teste em rede real: quem recebe a conexão é o notebook.

  -Texto/-Arquivo : manda bytes crus uma vez para a fila do Windows (teste direto do PC, ex.: SELFTEST).
  -Escutar        : servidor TCP; cada conexão vira um documento RAW na fila, com log. Só escuta
                    enquanto a impressora está presente no USB: fora dele a porta fica FECHADA
                    (conexão recusada), então o Print Bridge vê "offline" e segura o job na fila.

  Pré-requisito (uma vez, sem admin): fila RAW na porta USB da impressora, driver nativo do Windows:
    Add-PrinterDriver -Name 'Generic / Text Only'
    Add-Printer -Name 'TSC MB241 (RAW ponte)' -DriverName 'Generic / Text Only' -PortName USB001

  ATENÇÃO: -Escutar abre a porta 9100 do notebook para a rede. Rode só durante o teste e encerre
  depois (Ctrl+C ou fechar a janela).

.EXAMPLE
  pwsh -File tools\homologacao\ponte-usb-9100.ps1 -Texto "SELFTEST`r`n"
  pwsh -File tools\homologacao\ponte-usb-9100.ps1 -Escutar
#>
param(
    [string]$Fila = 'TSC MB241 (RAW ponte)',
    [string]$Texto,
    [string]$Arquivo,
    [switch]$Escutar,
    [int]$Porta = 9100,
    [string]$Log = (Join-Path $env:TEMP 'ponte-usb-9100.log')
)

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RawPrinter {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public class DOCINFO { public string pDocName; public string pOutputFile; public string pDataType; }
    [DllImport("winspool.drv", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool OpenPrinter(string name, out IntPtr h, IntPtr d);
    [DllImport("winspool.drv", SetLastError = true)] static extern bool ClosePrinter(IntPtr h);
    [DllImport("winspool.drv", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern int StartDocPrinter(IntPtr h, int level, DOCINFO di);
    [DllImport("winspool.drv", SetLastError = true)] static extern bool EndDocPrinter(IntPtr h);
    [DllImport("winspool.drv", SetLastError = true)] static extern bool StartPagePrinter(IntPtr h);
    [DllImport("winspool.drv", SetLastError = true)] static extern bool EndPagePrinter(IntPtr h);
    [DllImport("winspool.drv", SetLastError = true)] static extern bool WritePrinter(IntPtr h, byte[] b, int n, out int w);
    public static int Send(string printer, byte[] data, string doc) {
        IntPtr h;
        if (!OpenPrinter(printer, out h, IntPtr.Zero)) throw new System.ComponentModel.Win32Exception();
        try {
            if (StartDocPrinter(h, 1, new DOCINFO { pDocName = doc, pDataType = "RAW" }) == 0) throw new System.ComponentModel.Win32Exception();
            StartPagePrinter(h);
            int w; if (!WritePrinter(h, data, data.Length, out w)) throw new System.ComponentModel.Win32Exception();
            EndPagePrinter(h); EndDocPrinter(h);
            return w;
        } finally { ClosePrinter(h); }
    }
}
'@

function Write-Log([string]$m) { $l = "$(Get-Date -Format 'HH:mm:ss.fff') $m"; Write-Host $l; Add-Content -Path $Log -Value $l }

if ($Texto) { $n = [RawPrinter]::Send($Fila, [Text.Encoding]::GetEncoding(28591).GetBytes($Texto), 'teste direto'); Write-Log "direto: $n bytes"; return }
if ($Arquivo) { $n = [RawPrinter]::Send($Fila, [IO.File]::ReadAllBytes($Arquivo), (Split-Path $Arquivo -Leaf)); Write-Log "arquivo: $n bytes"; return }

if ($Escutar) {
    # Como uma TSC de rede: impressora fora do USB = porta FECHADA (conexão recusada), para o
    # Print Bridge ver "offline" e segurar o job na fila, em vez de o Windows engolir o trabalho.
    $portaFila = (Get-Printer -Name $Fila).PortName
    function Test-Presente { [bool](Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -like 'USBPRINT\*' -and $_.InstanceId -like "*$portaFila" }) }
    $srv = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $Porta)
    $ativo = $false; $proxCheck = [DateTime]::MinValue
    $i = 0
    while ($true) {
        if ((Get-Date) -ge $proxCheck) {
            $presente = Test-Presente; $proxCheck = (Get-Date).AddSeconds(2)
            if ($presente -and -not $ativo) { $srv.Start(); $ativo = $true; Write-Log "impressora presente em $portaFila -> escutando 0.0.0.0:$Porta" }
            elseif (-not $presente -and $ativo) { $srv.Stop(); $ativo = $false; Write-Log "impressora AUSENTE em $portaFila -> porta $Porta fechada (conexão recusada)" }
        }
        if (-not $ativo -or -not $srv.Pending()) { Start-Sleep -Milliseconds 200; continue }
        $c = $srv.AcceptTcpClient(); $i++
        $origem = $c.Client.RemoteEndPoint
        try {
            $ms = [IO.MemoryStream]::new(); $c.GetStream().CopyTo($ms)
            $bytes = $ms.ToArray()
            $inicio = [Text.Encoding]::GetEncoding(28591).GetString($bytes, 0, [Math]::Min(40, $bytes.Length)) -replace '[\r\n]+', ' | '
            if ($bytes.Length -gt 0) {
                $n = [RawPrinter]::Send($Fila, $bytes, "PrintBridge #$i")
                Write-Log "#$i de $origem : $($bytes.Length) bytes -> fila ($n escritos) [$inicio]"
            } else { Write-Log "#$i de $origem : conexão sem dados (teste de conexão)" }
        } catch { Write-Log "#$i ERRO: $($_.Exception.Message)" } finally { $c.Dispose() }
    }
}
