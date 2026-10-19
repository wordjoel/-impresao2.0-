using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Validation;

namespace CarrefourPrint.Bridge.Diagnostics;

/// <summary>Coleta os itens 1-4 do modo de diagnóstico (IP, porta, modelo, ZPL de teste) interativamente.</summary>
internal static class InteractivePrompt
{
    public static (string IpAddress, int Port, PrinterModel Model, string Payload, bool ExportReport) Collect()
    {
        Console.WriteLine("=== Carrefour Print Bridge (Windows) — Modo de Diagnóstico (interativo) ===");
        Console.WriteLine();

        string ip;
        while (true)
        {
            Console.Write("1) IP da impressora: ");
            ip = (Console.ReadLine() ?? "").Trim();
            if (NetworkAddressValidator.IsValidAddress(ip)) break;
            Console.WriteLine("   Endereço inválido — informe um IPv4 (ex.: 192.168.0.50) ou hostname.");
        }

        int port;
        while (true)
        {
            Console.Write("2) Porta [9100]: ");
            var raw = (Console.ReadLine() ?? "").Trim();
            if (raw.Length == 0) { port = 9100; break; }
            if (int.TryParse(raw, out port) && NetworkAddressValidator.IsValidPort(port)) break;
            Console.WriteLine("   Porta inválida — deve ser um número entre 1 e 65535.");
        }

        Console.WriteLine("Modelo da impressora sob teste:");
        Console.WriteLine("   1) TSC ML241");
        Console.WriteLine("   2) TSC MB241");
        Console.WriteLine("   3) Outra TSC");
        Console.WriteLine("   4) Genérica ZPL");
        PrinterModel model;
        while (true)
        {
            Console.Write("Escolha [1-4]: ");
            switch ((Console.ReadLine() ?? "").Trim())
            {
                case "1": model = PrinterModel.TscMl241; goto modelChosen;
                case "2": model = PrinterModel.TscMb241; goto modelChosen;
                case "3": model = PrinterModel.OtherTsc; goto modelChosen;
                case "4": model = PrinterModel.GenericZpl; goto modelChosen;
                default: Console.WriteLine("   Escolha inválida."); break;
            }
        }
        modelChosen:

        Console.WriteLine();
        Console.WriteLine("4) ZPL de teste — pressione Enter para usar a etiqueta de autoteste padrão,");
        Console.WriteLine("   ou cole seu próprio ZPL (finalize com uma linha em branco):");
        var customLines = new List<string>();
        while (true)
        {
            var line = Console.ReadLine();
            if (string.IsNullOrEmpty(line)) break;
            customLines.Add(line);
        }
        var payload = customLines.Count > 0 ? string.Join('\n', customLines) : DefaultTestLabel.Build(model);

        Console.Write("9) Exportar relatório de diagnóstico ao final? [S/n]: ");
        var exportAnswer = (Console.ReadLine() ?? "").Trim().ToLowerInvariant();
        var export = exportAnswer.Length == 0 || exportAnswer.StartsWith('s');

        return (ip, port, model, payload, export);
    }
}
