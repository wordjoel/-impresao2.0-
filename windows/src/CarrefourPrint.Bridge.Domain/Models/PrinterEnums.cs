namespace CarrefourPrint.Bridge.Domain.Models;

public enum PrinterModel
{
    TscMl241,
    TscMb241,
    OtherTsc,
    GenericZpl,
}

public enum PrinterLanguage
{
    Zpl,
    Tspl,
    Auto,
}

/// <summary>V1 implementa apenas Tcp — Bluetooth/Usb existem no enum como pontos de extensão futuros, igual ao Android.</summary>
public enum PrinterProtocol
{
    Tcp,
    Bluetooth,
    Usb,
}

public enum PrintPayloadType
{
    Zpl,
    Pdf,
    Raw,
}
