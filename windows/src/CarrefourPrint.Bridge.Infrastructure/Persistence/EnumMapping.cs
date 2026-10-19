using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Infrastructure.Persistence;

/// <summary>
/// Serialização de enums para o mesmo formato de string (UPPER_SNAKE_CASE) usado pelo Android
/// (Kotlin enum.name, ex.: "TSC_MB241", "PENDING") — mantém os valores conceitualmente idênticos
/// entre as duas plataformas mesmo cada uma persistindo em seu próprio banco local, e alinha com
/// os nomes de estado usados em CARREFOUR-PRINT-CROSS-PLATFORM.md.
/// </summary>
internal static class EnumMapping
{
    public static string ToWire(this JobStatus status) => status switch
    {
        JobStatus.Pending => "PENDING",
        JobStatus.Processing => "PROCESSING",
        JobStatus.Sent => "SENT",
        JobStatus.Failed => "FAILED",
        JobStatus.Cancelled => "CANCELLED",
        _ => throw new ArgumentOutOfRangeException(nameof(status)),
    };

    public static JobStatus JobStatusFromWire(string value) => value switch
    {
        "PENDING" => JobStatus.Pending,
        "PROCESSING" => JobStatus.Processing,
        "SENT" => JobStatus.Sent,
        "FAILED" => JobStatus.Failed,
        "CANCELLED" => JobStatus.Cancelled,
        _ => JobStatus.Pending, // fallback conservador — mesmo padrão do mapper Android (runCatching { ... }.getOrDefault(PENDING))
    };

    public static string ToWire(this PrintPayloadType type) => type switch
    {
        PrintPayloadType.Zpl => "ZPL",
        PrintPayloadType.Pdf => "PDF",
        PrintPayloadType.Raw => "RAW",
        _ => throw new ArgumentOutOfRangeException(nameof(type)),
    };

    public static PrintPayloadType PayloadTypeFromWire(string value) => value switch
    {
        "ZPL" => PrintPayloadType.Zpl,
        "PDF" => PrintPayloadType.Pdf,
        "RAW" => PrintPayloadType.Raw,
        _ => PrintPayloadType.Zpl,
    };

    public static string ToWire(this PrinterModel model) => model switch
    {
        PrinterModel.TscMl241 => "TSC_ML241",
        PrinterModel.TscMb241 => "TSC_MB241",
        PrinterModel.OtherTsc => "OTHER_TSC",
        PrinterModel.GenericZpl => "GENERIC_ZPL",
        _ => throw new ArgumentOutOfRangeException(nameof(model)),
    };

    public static PrinterModel ModelFromWire(string value) => value switch
    {
        "TSC_ML241" => PrinterModel.TscMl241,
        "TSC_MB241" => PrinterModel.TscMb241,
        "OTHER_TSC" => PrinterModel.OtherTsc,
        _ => PrinterModel.GenericZpl,
    };

    public static string ToWire(this PrinterLanguage language) => language switch
    {
        PrinterLanguage.Zpl => "ZPL",
        PrinterLanguage.Tspl => "TSPL",
        PrinterLanguage.Auto => "AUTO",
        _ => throw new ArgumentOutOfRangeException(nameof(language)),
    };

    public static PrinterLanguage LanguageFromWire(string value) => value switch
    {
        "TSPL" => PrinterLanguage.Tspl,
        "AUTO" => PrinterLanguage.Auto,
        _ => PrinterLanguage.Zpl,
    };

    public static string ToWire(this PrinterProtocol protocol) => protocol switch
    {
        PrinterProtocol.Tcp => "TCP",
        PrinterProtocol.Bluetooth => "BLUETOOTH",
        PrinterProtocol.Usb => "USB",
        _ => throw new ArgumentOutOfRangeException(nameof(protocol)),
    };

    public static PrinterProtocol ProtocolFromWire(string value) => value switch
    {
        "BLUETOOTH" => PrinterProtocol.Bluetooth,
        "USB" => PrinterProtocol.Usb,
        _ => PrinterProtocol.Tcp,
    };
}
