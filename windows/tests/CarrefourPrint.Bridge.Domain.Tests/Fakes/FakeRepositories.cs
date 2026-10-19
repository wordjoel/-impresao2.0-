using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.Tests.Fakes;

/// <summary>Espelha testutil/FakeRepositories.kt (Android) — mesmos fakes, mesma semântica.</summary>
public sealed class FakePrinterRepository : IPrinterRepository
{
    private List<Printer> _state = [];

    public Task<IReadOnlyList<Printer>> GetAllAsync(CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<Printer>>(_state);

    public Task<Printer?> GetByIdAsync(string id, CancellationToken ct = default) =>
        Task.FromResult(_state.FirstOrDefault(p => p.Id == id));

    public Task<Printer?> GetDefaultAsync(CancellationToken ct = default) =>
        Task.FromResult(_state.FirstOrDefault(p => p.IsDefault));

    public Task UpsertAsync(Printer printer, CancellationToken ct = default)
    {
        var withoutExisting = _state.Where(p => p.Id != printer.Id).ToList();
        _state = printer.IsDefault
            ? withoutExisting.Select(p => p with { IsDefault = false }).Append(printer).ToList()
            : withoutExisting.Append(printer).ToList();
        return Task.CompletedTask;
    }

    public Task DeleteAsync(string id, CancellationToken ct = default)
    {
        _state = _state.Where(p => p.Id != id).ToList();
        return Task.CompletedTask;
    }

    public Task SetDefaultAsync(string id, CancellationToken ct = default)
    {
        _state = _state.Select(p => p with { IsDefault = p.Id == id }).ToList();
        return Task.CompletedTask;
    }

    public Task SetEnabledAsync(string id, bool enabled, CancellationToken ct = default)
    {
        _state = _state.Select(p => p.Id == id ? p with { Enabled = enabled } : p).ToList();
        return Task.CompletedTask;
    }

    public void Seed(Printer printer) => _state = _state.Append(printer).ToList();
}

public sealed class FakePrintJobRepository : IPrintJobRepository
{
    private List<PrintJob> _state = [];
    public IReadOnlyList<PrintJob> Snapshot => _state;

    public Task<IReadOnlyList<PrintJob>> GetActiveAsync(CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<PrintJob>>(_state.Where(j => j.Status.IsActive()).ToList());

    public Task<IReadOnlyList<PrintJob>> GetHistoryAsync(CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<PrintJob>>(_state.Where(j => !j.Status.IsActive()).ToList());

    public Task<PrintJob?> GetByIdAsync(string id, CancellationToken ct = default) =>
        Task.FromResult(_state.FirstOrDefault(j => j.Id == id));

    public Task InsertAsync(PrintJob job, CancellationToken ct = default)
    {
        _state = _state.Append(job).ToList();
        return Task.CompletedTask;
    }

    public Task UpdateAsync(PrintJob job, CancellationToken ct = default)
    {
        _state = _state.Select(j => j.Id == job.Id ? job : j).ToList();
        return Task.CompletedTask;
    }

    public Task<PrintJob?> NextPendingForAsync(string printerId, CancellationToken ct = default) =>
        Task.FromResult(_state.Where(j => j.PrinterId == printerId && j.Status == JobStatus.Pending)
            .OrderBy(j => j.CreatedAt).FirstOrDefault());

    public Task<IReadOnlyList<PrintJob>> FindOrphanedProcessingAsync(CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<PrintJob>>(_state.Where(j => j.Status == JobStatus.Processing).ToList());

    public Task CancelAsync(string id, CancellationToken ct = default)
    {
        _state = _state.Select(j =>
            j.Id == id && (j.Status == JobStatus.Pending || j.Status == JobStatus.Failed)
                ? j with { Status = JobStatus.Cancelled }
                : j).ToList();
        return Task.CompletedTask;
    }

    public Task PurgeHistoryOlderThanAsync(long timestampMillis, CancellationToken ct = default)
    {
        _state = _state.Where(j => j.Status.IsActive() || j.UpdatedAt >= timestampMillis).ToList();
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<string>> PrinterIdsWithPendingJobsAsync(CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<string>>(_state.Where(j => j.Status == JobStatus.Pending)
            .Select(j => j.PrinterId).Distinct().ToList());
}

public sealed class FakeConfigurationRepository(AppConfiguration? initial = null) : IConfigurationRepository
{
    private AppConfiguration _state = initial ?? new AppConfiguration();

    public Task<AppConfiguration> CurrentAsync(CancellationToken ct = default) => Task.FromResult(_state);

    public Task UpdateAsync(Func<AppConfiguration, AppConfiguration> transform, CancellationToken ct = default)
    {
        _state = transform(_state);
        return Task.CompletedTask;
    }
}

public sealed class FakeLicenseRepository(LicenseState? initial = null) : ILicenseRepository
{
    private LicenseState _state = initial ?? new LicenseState();

    public Task<LicenseState> CurrentAsync(CancellationToken ct = default) => Task.FromResult(_state);

    public Task UpdateAsync(Func<LicenseState, LicenseState> transform, CancellationToken ct = default)
    {
        _state = transform(_state);
        return Task.CompletedTask;
    }
}

public sealed class FakeNetworkStateProvider(bool available = true) : INetworkStateProvider
{
    public bool Available { get; set; } = available;
    public bool IsNetworkAvailable() => Available;
}

public sealed class FakeQueueDispatcher : IQueueDispatcher
{
    public int RequestCount { get; private set; }
    public void RequestProcessing() => RequestCount++;
}

/// <summary>
/// Fake de IDeviceIdentityProvider para testar ORQUESTRAÇÃO (RespondToDeviceChallengeUseCase),
/// nunca para testar a garantia criptográfica em si — essa só existe de verdade contra o
/// Android Keystore/Windows CNG reais (ver WindowsCngDeviceIdentityProviderTests, Infrastructure).
/// </summary>
public sealed class FakeDeviceIdentityProvider(string deviceId = "fake-device-id") : IDeviceIdentityProvider
{
    public byte[]? LastSignedChallenge { get; private set; }

    public string DeviceId() => deviceId;
    public byte[] PublicKey() => "fake-public-key"u8.ToArray();

    public byte[] Sign(byte[] challenge)
    {
        LastSignedChallenge = challenge;
        // Assinatura fake determinística (challenge invertido) — só para provar que o use case
        // passa os bytes certos adiante e devolve o resultado certo, nunca usada para verificar
        // uma propriedade criptográfica real.
        var reversed = (byte[])challenge.Clone();
        Array.Reverse(reversed);
        return reversed;
    }
}

/// <summary>Espelha testutil/FakePrinterTransport.kt (Android) — mesmos construtores nomeados.</summary>
public sealed class FakePrinterTransport : IPrinterTransport
{
    private readonly Func<Printer, byte[], TransportResult> _behavior;

    private FakePrinterTransport(Func<Printer, byte[], TransportResult> behavior) => _behavior = behavior;

    public static FakePrinterTransport AlwaysSucceeds() =>
        new((_, payload) => new TransportResult.Success(payload.Length, 1));

    public static FakePrinterTransport AlwaysFails(TransportFailureReason reason) =>
        new((_, _) => new TransportResult.Failure(reason, reason.ToString()));

    public static FakePrinterTransport SucceedsOnAttempt(int attemptNumber)
    {
        var attempt = 0;
        return new FakePrinterTransport((_, payload) =>
        {
            attempt++;
            return attempt >= attemptNumber
                ? new TransportResult.Success(payload.Length, 1)
                : new TransportResult.Failure(TransportFailureReason.ConnectionRefused, "simulated");
        });
    }

    public Task<TransportResult> SendAsync(Printer printer, byte[] payload, CancellationToken ct = default) =>
        Task.FromResult(_behavior(printer, payload));
}
