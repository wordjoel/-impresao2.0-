using System.Collections.Concurrent;

namespace CarrefourPrint.Backend.Reference;

public sealed record DeviceActivationRecord
{
    public required string DeviceId { get; init; }
    public required string LicenseId { get; init; }
    public required string TenantId { get; init; }
    public required DateTimeOffset ActivatedAtUtc { get; init; }
}

/// <summary>
/// Nenhum destes é um erro de sistema — todos são decisões de negócio determinísticas do checklist
/// SERVER VALIDATION (`CARREFOUR-PRINT-SERVER-LICENSING.md` §4).
/// </summary>
public enum ActivationResult
{
    /// <summary>Primeira ativação bem-sucedida deste device nesta license — um seat novo foi consumido.</summary>
    Activated,

    /// <summary>
    /// Este mesmo device já está ativo nesta mesma license — idempotente por design: retry de uma
    /// chamada de ativação (ex.: cliente reenviando após timeout de rede na resposta anterior,
    /// cenário comum, não hipotético) NUNCA consome um segundo seat nem cria um segundo registro.
    /// </summary>
    AlreadyActivated,

    /// <summary>`COUNT(Device ACTIVE) &gt;= maxDevices` — o sexto dispositivo do exemplo de `CARREFOUR-PRINT-SERVER-LICENSING.md` §1.</summary>
    SeatsExhausted,
}

/// <summary>
/// Implementa o núcleo do checklist SERVER VALIDATION (`CARREFOUR-PRINT-SERVER-LICENSING.md` §4,
/// passos 4-5) como código testável, com a mesma disciplina de "transaction/lock apropriado" já
/// usada para a fila de impressão (`PrintQueue.cs`) e para o pareamento em escala
/// (`CARREFOUR-PRINT-PROVISIONING.md` §5, passo 8) — a contagem de seats é sempre uma operação
/// atômica (CAS via `ConcurrentDictionary.TryUpdate`), nunca "ler a contagem, decidir, escrever" em
/// passos separados que um agendador de threads pudesse intercalar.
/// </summary>
public sealed class DeviceActivationService(TimeProvider clock)
{
    private readonly ConcurrentDictionary<(string LicenseId, string DeviceId), DeviceActivationRecord> _activations = new();
    private readonly ConcurrentDictionary<string, int> _seatCount = new();

    public ActivationResult Activate(string tenantId, string licenseId, string deviceId, int maxDevices)
    {
        var key = (licenseId, deviceId);

        // Duplicidade primeiro, sem reivindicar seat — checagem barata que cobre o caso comum
        // (retry rápido, sem corrida real) sem gastar um seat só para devolvê-lo em seguida.
        if (_activations.ContainsKey(key))
        {
            return ActivationResult.AlreadyActivated;
        }

        if (!TryClaimSeat(licenseId, maxDevices))
        {
            return ActivationResult.SeatsExhausted;
        }

        var record = new DeviceActivationRecord
        {
            DeviceId = deviceId,
            LicenseId = licenseId,
            TenantId = tenantId,
            ActivatedAtUtc = clock.GetUtcNow(),
        };

        if (_activations.TryAdd(key, record))
        {
            return ActivationResult.Activated;
        }

        // Corrida genuína: duas chamadas concorrentes para o MESMO device+license passaram a
        // checagem de duplicidade antes de qualquer uma inserir (nenhuma tinha visto a outra
        // ainda) — cada uma reivindicou um seat, mas só uma pode "vencer" o registro da
        // ativação. A perdedora devolve o seat que reivindicou por engano — nunca deixa um seat
        // "vazado" contando para uma ativação duplicada que não existe de verdade.
        ReleaseSeat(licenseId);
        return ActivationResult.AlreadyActivated;
    }

    private bool TryClaimSeat(string licenseId, int maxDevices)
    {
        while (true)
        {
            var current = _seatCount.GetOrAdd(licenseId, 0);
            if (current >= maxDevices)
            {
                return false;
            }

            if (_seatCount.TryUpdate(licenseId, current + 1, current))
            {
                return true;
            }
            // CAS perdeu para outra chamada concorrente — relê e tenta de novo.
        }
    }

    private void ReleaseSeat(string licenseId)
    {
        while (true)
        {
            var current = _seatCount[licenseId];
            if (_seatCount.TryUpdate(licenseId, current - 1, current))
            {
                return;
            }
        }
    }

    /// <summary>Exposto só para asserção em teste — nunca usado por lógica de negócio (a contagem em si nunca deve ser lida e decidida em dois passos separados fora de <see cref="Activate"/>).</summary>
    public int CurrentSeatCount(string licenseId) => _seatCount.GetValueOrDefault(licenseId);
}
