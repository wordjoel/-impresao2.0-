namespace CarrefourPrint.Bridge.Domain.Models;

/// <summary>
/// SENT significa apenas "os bytes foram entregues com sucesso ao transporte" (o socket TCP
/// aceitou e recebeu todo o payload sem exceção). NÃO significa que a etiqueta foi fisicamente
/// impressa — a TSC não confirma isso de volta nesta v1. Nunca renomeie SENT para PRINTED sem
/// antes implementar um canal de confirmação física real. Espelha exatamente
/// br.com.atos.printbridge.domain.model.PrintJobStatus (Android) — mesmo contrato,
/// CARREFOUR-PRINT-CROSS-PLATFORM.md §7.
/// </summary>
public enum JobStatus
{
    Pending,
    Processing,
    Sent,
    Failed,
    Cancelled,
}

public static class JobStatusExtensions
{
    public static bool IsActive(this JobStatus status) =>
        status is JobStatus.Pending or JobStatus.Processing;
}
