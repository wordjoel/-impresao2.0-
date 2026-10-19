using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Diagnostics;

/// <summary>
/// Parser de argumentos de linha de comando para uso não-interativo/roteirizado do modo de
/// diagnóstico (ex.: chamado por um script de homologação em campo, ou por suporte remoto).
/// Sem argumentos, o programa cai no modo interativo (InteractivePrompt).
/// </summary>
public sealed class CliOptions
{
    public bool ShowHelp { get; private init; }
    public bool IsNonInteractive { get; private init; }
    public string IpAddress { get; private init; } = "";
    public int Port { get; private init; } = 9100;
    public PrinterModel Model { get; private init; } = PrinterModel.GenericZpl;
    public string? Zpl { get; private init; }
    public string? ZplFile { get; private init; }
    public bool ExportReport { get; private init; } = true;

    public static CliOptions Parse(string[] args)
    {
        if (args.Length == 0) return new CliOptions { IsNonInteractive = false };
        if (args.Contains("--help") || args.Contains("-h")) return new CliOptions { ShowHelp = true };

        var options = new CliOptions { IsNonInteractive = true };
        string? ip = null;
        var port = 9100;
        var model = PrinterModel.GenericZpl;
        string? zpl = null;
        string? zplFile = null;
        var export = true;

        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--ip" when i + 1 < args.Length:
                    ip = args[++i];
                    break;
                case "--port" when i + 1 < args.Length:
                    port = int.Parse(args[++i]);
                    break;
                case "--model" when i + 1 < args.Length:
                    model = ParseModel(args[++i]);
                    break;
                case "--zpl" when i + 1 < args.Length:
                    zpl = args[++i];
                    break;
                case "--zpl-file" when i + 1 < args.Length:
                    zplFile = args[++i];
                    break;
                case "--no-export":
                    export = false;
                    break;
            }
        }

        if (ip is null) throw new ArgumentException("--ip é obrigatório em modo não-interativo. Use --help para ver as opções.");

        return new CliOptions
        {
            IsNonInteractive = true,
            IpAddress = ip,
            Port = port,
            Model = model,
            Zpl = zpl,
            ZplFile = zplFile,
            ExportReport = export,
        };
    }

    public (string IpAddress, int Port, PrinterModel Model, string Payload, bool ExportReport) ToRunParameters()
    {
        var payload = Zpl
            ?? (ZplFile is not null ? File.ReadAllText(ZplFile) : DefaultTestLabel.Build(Model));
        return (IpAddress, Port, Model, payload, ExportReport);
    }

    private static PrinterModel ParseModel(string value) => value.ToLowerInvariant() switch
    {
        "tsc-ml241" or "ml241" => PrinterModel.TscMl241,
        "tsc-mb241" or "mb241" => PrinterModel.TscMb241,
        "outra-tsc" or "other-tsc" => PrinterModel.OtherTsc,
        _ => PrinterModel.GenericZpl,
    };

    public static void PrintUsage()
    {
        Console.WriteLine("""
            Carrefour Print Bridge (Windows) — Modo de Diagnóstico

            Uso interativo (recomendado para homologação em campo):
              CarrefourPrintBridgeDiagnostics

            Uso não-interativo (roteirizado):
              CarrefourPrintBridgeDiagnostics --ip <IP> [--port 9100] [--model tsc-ml241|tsc-mb241|outra-tsc|generico]
                                               [--zpl "<conteudo>" | --zpl-file <caminho>] [--no-export]

            Exemplos:
              CarrefourPrintBridgeDiagnostics --ip 192.168.0.50 --model tsc-mb241
              CarrefourPrintBridgeDiagnostics --ip 192.168.0.51 --port 9100 --zpl-file etiqueta-teste.zpl

            IMPORTANTE: um resultado SENT confirma apenas que os bytes foram entregues ao socket da
            impressora — NUNCA que a etiqueta foi fisicamente impressa. Homologação requer
            confirmação física do hardware em campo, sempre pendente até esse teste real.
            """);
    }
}
