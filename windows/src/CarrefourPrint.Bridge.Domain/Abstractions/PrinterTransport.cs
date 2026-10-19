using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Domain.Abstractions;

/// <summary>Espelha br.com.atos.printbridge.domain.printing.TransportFailureReason (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §8.1 (namespace TRANSPORT_*).</summary>
public enum TransportFailureReason
{
    InvalidAddress,
    NoNetwork,
    ConnectionRefused,
    Timeout,
    IoError,
    Unknown,
}

public static class TransportFailureReasonExtensions
{
    public static string OperatorMessage(this TransportFailureReason reason) => reason switch
    {
        TransportFailureReason.InvalidAddress => "Endereço inválido",
        TransportFailureReason.NoNetwork => "Dispositivo Windows sem conexão com a rede",
        TransportFailureReason.ConnectionRefused => "Conexão recusada",
        TransportFailureReason.Timeout => "Tempo de conexão esgotado",
        TransportFailureReason.IoError => "Falha ao enviar dados para a impressora",
        TransportFailureReason.Unknown => "Erro desconhecido",
        _ => throw new ArgumentOutOfRangeException(nameof(reason)),
    };

    /// <summary>Wire code estável de CARREFOUR-PRINT-CROSS-PLATFORM.md §8.1 — nunca renomear sem atualizar o contrato.</summary>
    public static string WireErrorCode(this TransportFailureReason reason) => reason switch
    {
        TransportFailureReason.InvalidAddress => "TRANSPORT_INVALID_ADDRESS",
        TransportFailureReason.NoNetwork => "TRANSPORT_NO_NETWORK",
        TransportFailureReason.ConnectionRefused => "TRANSPORT_CONNECTION_REFUSED",
        TransportFailureReason.Timeout => "TRANSPORT_TIMEOUT",
        TransportFailureReason.IoError => "TRANSPORT_IO_ERROR",
        TransportFailureReason.Unknown => "TRANSPORT_UNKNOWN",
        _ => throw new ArgumentOutOfRangeException(nameof(reason)),
    };
}

public abstract record TransportResult
{
    public sealed record Success(int BytesSent, long DurationMillis) : TransportResult;
    public sealed record Failure(TransportFailureReason Reason, string TechnicalMessage) : TransportResult;
}

/// <summary>Espelha br.com.atos.printbridge.domain.printing.PrinterTransport (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §12.</summary>
public interface IPrinterTransport
{
    Task<TransportResult> SendAsync(Printer printer, byte[] payload, CancellationToken ct = default);
}

public sealed record ConnectionTestResult(bool Reachable, string Message, long ElapsedMillis);

/// <summary>
/// Espelha br.com.atos.printbridge.domain.printing.PrinterConnectionTester (Android) — só abre e
/// fecha o socket (probe de alcançabilidade), NUNCA escreve payload de impressão real. Usado pelo
/// modo de diagnóstico para separar "a impressora está acessível na rede" de "os bytes de teste
/// foram entregues" — são duas perguntas diferentes.
/// </summary>
public interface IPrinterConnectionTester
{
    Task<ConnectionTestResult> TestAsync(Printer printer, CancellationToken ct = default);
}

/// <summary>Espelha br.com.atos.printbridge.domain.network.NetworkStateProvider (Android).</summary>
public interface INetworkStateProvider
{
    bool IsNetworkAvailable();
}

/// <summary>Espelha br.com.atos.printbridge.domain.queue.QueueDispatcher (Android).</summary>
public interface IQueueDispatcher
{
    void RequestProcessing();
}

/// <summary>Espelha br.com.atos.printbridge.domain.license.DeviceIdentityProvider (Android) — CARREFOUR-PRINT-CROSS-PLATFORM.md §2.</summary>
public interface IDeviceIdentityProvider
{
    string DeviceId();
    byte[] PublicKey();
    byte[] Sign(byte[] challenge);
}
