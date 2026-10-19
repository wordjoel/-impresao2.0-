using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Diagnostics;

/// <summary>
/// Etiqueta ZPL de autoteste — dimensionada para 203dpi (padrão TSC ML241/MB241), com timestamp
/// embutido para diferenciar execuções sucessivas na mesma bobina de teste. Puramente texto ZPL
/// (nenhuma lib de geração de imagem/rasterização) — mesmo princípio "payload opaco" do resto do
/// sistema (CARREFOUR-PRINT-CROSS-PLATFORM.md §6).
/// </summary>
internal static class DefaultTestLabel
{
    public static string Build(PrinterModel model)
    {
        var modelLabel = model switch
        {
            PrinterModel.TscMl241 => "TSC ML241",
            PrinterModel.TscMb241 => "TSC MB241",
            PrinterModel.OtherTsc => "TSC (OUTRO MODELO)",
            _ => "GENERICA ZPL",
        };
        var timestamp = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd HH:mm:ss") + "Z";

        return $"""
            ^XA
            ^FO30,30^A0N,40,40^FDCARREFOUR PRINT BRIDGE^FS
            ^FO30,80^A0N,30,30^FDDIAGNOSTICO - {modelLabel}^FS
            ^FO30,120^A0N,25,25^FD{timestamp}^FS
            ^FO30,160^A0N,20,20^FDSENT != PRINTED - confirmar fisicamente^FS
            ^XZ
            """.ReplaceLineEndings("\n");
    }
}
