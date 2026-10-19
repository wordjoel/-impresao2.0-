using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.UseCases;

/// <summary>
/// Espelha br.com.atos.printbridge.application.service.PrintEngine (Android) 1:1, incluindo a
/// regra de repetição de cópias — CARREFOUR-PRINT-CROSS-PLATFORM.md §6: body = payload repetido
/// `copies` vezes, codificado UTF-8, sem separador. Qualquer divergência aqui muda o resultado
/// físico impresso e quebra a paridade Android/Windows.
/// </summary>
public sealed class PrintEngine(IPrinterTransport tcpTransport)
{
    public async Task<TransportResult> SendAsync(Printer printer, PrintJob job, CancellationToken ct = default)
    {
        if (printer.Protocol != PrinterProtocol.Tcp)
        {
            var name = printer.Protocol == PrinterProtocol.Bluetooth ? "Bluetooth" : "USB";
            return new TransportResult.Failure(TransportFailureReason.Unknown, $"Transporte {name} ainda não implementado");
        }

        var body = job.Copies > 1 ? string.Concat(Enumerable.Repeat(job.Payload, job.Copies)) : job.Payload;
        var payloadBytes = Encoding.UTF8.GetBytes(body);
        return await tcpTransport.SendAsync(printer, payloadBytes, ct);
    }
}
