using CarrefourPrint.Bridge.Domain.Abstractions;

namespace CarrefourPrint.Bridge.Worker;

/// <summary>
/// Equivalente de WorkManagerQueueDispatcher.requestProcessing (Android) — em vez de enfileirar
/// um WorkRequest no WorkManager, sinaliza o QueueProcessingWorker (BackgroundService já em
/// execução no mesmo processo) para acordar imediatamente, em vez de esperar o próximo tick da
/// rede de segurança periódica.
/// </summary>
public sealed class InProcessQueueDispatcher : IQueueDispatcher
{
    private readonly SemaphoreSlim _signal = new(0, int.MaxValue);

    public void RequestProcessing()
    {
        if (_signal.CurrentCount == 0) _signal.Release();
    }

    public async Task WaitForSignalAsync(TimeSpan timeout, CancellationToken ct)
    {
        try
        {
            await _signal.WaitAsync(timeout, ct);
        }
        catch (OperationCanceledException)
        {
            // Encerramento do serviço — o BackgroundService trata o cancelamento no laço principal.
        }
    }
}
