using System.Collections.Concurrent;

namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Um `PrintJob` endereçado a um `printerId` que o device não está autorizado a usar precisa ser
/// recusado ANTES de qualquer bilhete chegar perto de uma fila local — nunca só "impressora não
/// encontrada" (que soa a um erro de cadastro, não a uma tentativa de acessar algo fora do
/// escopo). O conjunto de impressoras autorizadas de um device vem de
/// `CARREFOUR-PRINT-PROVISIONING.md` §1 (`ProvisioningProfile.allowedPrinters`) — este tipo é a
/// checagem de autorização em si, agnóstica de como o conjunto foi populado.
///
/// **Fail-closed por construção:** um device nunca visto (`TryGetValue` falha) ou uma impressora
/// fora do conjunto registrado nunca são tratados como "permitir por padrão" — o único caminho que
/// retorna `true` é uma correspondência explícita.
/// </summary>
public sealed class AuthorizedPrinterRegistry
{
    private readonly ConcurrentDictionary<string, HashSet<string>> _authorizedByDevice = new();

    public void Authorize(string deviceId, IEnumerable<string> printerIds) =>
        _authorizedByDevice[deviceId] = [.. printerIds];

    public bool IsAuthorized(string deviceId, string printerId) =>
        _authorizedByDevice.TryGetValue(deviceId, out var printers) && printers.Contains(printerId);
}
