using System.Text;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Infrastructure.Logging;

/// <summary>
/// Exporta o relatório do modo de diagnóstico (item 9 do pedido do usuário). O relatório sempre
/// carrega, de forma não-condicional, a explicação técnica de que SENT significa apenas "dados
/// entregues ao endpoint da impressora" e que a impressão física NÃO foi confirmada — nunca
/// omitido, nunca dependente de flag, para que ninguém copie e cole só o resultado sem o aviso.
/// </summary>
public sealed class PrinterDiagnosticReportExporter(IDeviceIdentityProvider deviceIdentityProvider, string reportsDirectory)
{
    public string Export(PrinterDiagnosticResult result)
    {
        Directory.CreateDirectory(reportsDirectory);
        var timestamp = result.StartedAt.ToString("yyyyMMdd_HHmmss");
        var path = Path.Combine(reportsDirectory, $"diagnostico_impressora_{timestamp}.txt");

        var builder = new StringBuilder();
        builder.AppendLine("Carrefour Print Bridge (Windows) — Relatório de Diagnóstico de Impressora");
        builder.AppendLine("========================================================================");
        builder.AppendLine();
        builder.AppendLine($"Device ID: {deviceIdentityProvider.DeviceId()}");
        builder.AppendLine($"Horário de início (UTC): {result.StartedAt:O}");
        builder.AppendLine();
        builder.AppendLine("1-2. Endpoint testado:");
        builder.AppendLine($"   IP: {result.IpAddress}");
        builder.AppendLine($"   Porta: {result.Port}");
        builder.AppendLine($"   Modelo declarado: {DescribeModel(result.Model)}");
        builder.AppendLine();
        builder.AppendLine("3. Teste de conectividade (probe de socket, sem payload):");
        builder.AppendLine($"   Alcançável: {(result.ConnectivityReachable ? "SIM" : "NÃO")}");
        builder.AppendLine($"   Mensagem: {result.ConnectivityMessage}");
        builder.AppendLine($"   Tempo decorrido: {result.ConnectivityElapsedMillis} ms");
        builder.AppendLine();
        builder.AppendLine("4. Envio de ZPL de teste:");
        builder.AppendLine($"   Payload (bytes UTF-8 declarados): {Encoding.UTF8.GetByteCount(result.TestPayload)}");
        builder.AppendLine($"   Conteúdo ZPL enviado:");
        foreach (var line in result.TestPayload.Split('\n'))
        {
            builder.AppendLine($"      {line}");
        }
        builder.AppendLine();
        builder.AppendLine("5. Horário registrado (UTC): " + result.StartedAt.ToString("O"));
        builder.AppendLine();
        builder.AppendLine("6. Quantidade de bytes efetivamente entregues ao transporte: " +
            (result.SendOutcome == DiagnosticSendOutcome.Sent ? result.BytesSent.ToString() : "0 (envio falhou antes de completar)"));
        builder.AppendLine();
        builder.AppendLine("7. RESULTADO: " + (result.SendOutcome == DiagnosticSendOutcome.Sent ? "SENT" : "FAILED"));
        builder.AppendLine($"   Tempo de envio: {result.SendElapsedMillis} ms");
        builder.AppendLine();
        builder.AppendLine("8. Erro técnico:");
        builder.AppendLine(result.TechnicalError is null
            ? "   (nenhum — envio concluído sem exceção)"
            : $"   {result.WireErrorCode}: {result.TechnicalError}");
        builder.AppendLine();
        builder.AppendLine("------------------------------------------------------------------------");
        builder.AppendLine("EXPLICAÇÃO TÉCNICA OBRIGATÓRIA — LEIA ANTES DE INTERPRETAR O RESULTADO:");
        builder.AppendLine();
        builder.AppendLine("\"SENT\" significa exclusivamente que os dados foram entregues ao endpoint");
        builder.AppendLine("TCP/IP da impressora (o socket aceitou e recebeu o payload sem exceção).");
        builder.AppendLine("A impressão física NÃO foi confirmada por este teste — não existe, nesta");
        builder.AppendLine("versão do sistema (Android ou Windows), nenhum canal de confirmação de que");
        builder.AppendLine("a etiqueta saiu fisicamente da impressora (papel, fita, cabeça de impressão,");
        builder.AppendLine("sensor de proximidade — nada disso é verificado aqui).");
        builder.AppendLine();
        builder.AppendLine("Este relatório NÃO constitui homologação do hardware TSC ML241/MB241 nem de");
        builder.AppendLine("qualquer outra impressora. Homologação exige confirmação física em campo —");
        builder.AppendLine("etiqueta realmente impressa e legível, qualidade de impressão avaliada,");
        builder.AppendLine("comportamento do sensor de papel/ribbon observado — que só é possível com o");
        builder.AppendLine("hardware físico respondendo, e permanece PENDENTE até essa validação real.");
        builder.AppendLine("------------------------------------------------------------------------");

        File.WriteAllText(path, builder.ToString());
        return path;
    }

    private static string DescribeModel(Domain.Models.PrinterModel model) => model switch
    {
        Domain.Models.PrinterModel.TscMl241 => "TSC ML241",
        Domain.Models.PrinterModel.TscMb241 => "TSC MB241",
        Domain.Models.PrinterModel.OtherTsc => "Outra TSC",
        Domain.Models.PrinterModel.GenericZpl => "Genérica ZPL",
        _ => model.ToString(),
    };
}
