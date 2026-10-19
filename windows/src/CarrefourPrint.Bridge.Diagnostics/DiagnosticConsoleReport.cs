using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Diagnostics;

/// <summary>
/// Imprime os itens 3-8 do modo de diagnóstico no console. O resultado exibido é SEMPRE "SENT"
/// ou "FAILED" — nunca "PRINTED", em nenhuma condição, porque esse valor não existe em
/// DiagnosticSendOutcome (CARREFOUR-PRINT-CROSS-PLATFORM.md §7). A explicação técnica é impressa
/// sempre, mesmo em caso de sucesso — não é um aviso "só quando dá errado".
/// </summary>
internal static class DiagnosticConsoleReport
{
    public static void Print(PrinterDiagnosticResult result)
    {
        Console.WriteLine("--- 3) Teste de conectividade (probe de socket) ---");
        Console.WriteLine($"Alcançável: {(result.ConnectivityReachable ? "SIM" : "NÃO")} ({result.ConnectivityMessage}, {result.ConnectivityElapsedMillis} ms)");
        Console.WriteLine();

        Console.WriteLine("--- 4-8) Envio de ZPL de teste ---");
        Console.WriteLine($"5) Horário (UTC): {result.StartedAt:O}");
        Console.WriteLine($"6) Bytes entregues ao transporte: {(result.SendOutcome == DiagnosticSendOutcome.Sent ? result.BytesSent : 0)}");

        var resultLabel = result.SendOutcome == DiagnosticSendOutcome.Sent ? "SENT" : "FAILED";
        var color = result.SendOutcome == DiagnosticSendOutcome.Sent ? ConsoleColor.Green : ConsoleColor.Red;
        Console.Write("7) RESULTADO: ");
        var previous = Console.ForegroundColor;
        Console.ForegroundColor = color;
        Console.WriteLine(resultLabel);
        Console.ForegroundColor = previous;

        Console.WriteLine($"8) Erro técnico: {(result.TechnicalError is null ? "(nenhum)" : $"{result.WireErrorCode}: {result.TechnicalError}")}");
        Console.WriteLine();

        Console.WriteLine("========================================================================");
        Console.WriteLine("SENT significa apenas que os dados foram entregues ao endpoint TCP/IP da");
        Console.WriteLine("impressora. A impressão física NÃO foi confirmada — não existe canal de");
        Console.WriteLine("confirmação física nesta versão. Isto NÃO é homologação: confirme");
        Console.WriteLine("visualmente se a etiqueta saiu fisicamente da impressora antes de declarar");
        Console.WriteLine("qualquer coisa homologada.");
        Console.WriteLine("========================================================================");
    }
}
