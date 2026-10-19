using System.Collections.Concurrent;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace CarrefourPrint.Bridge.Infrastructure.Logging;

/// <summary>
/// Mantém em memória os últimos eventos estruturados, para o pacote de diagnóstico
/// (DiagnosticsExporter) — equivalente exato de Logger.recentEvents() (Android, ArrayDeque com
/// capacidade fixa de 500).
/// </summary>
public sealed class RecentEventsBuffer
{
    private const int Capacity = 500;
    private readonly object _lock = new();
    private readonly Queue<string> _buffer = new();

    public void Add(string line)
    {
        lock (_lock)
        {
            if (_buffer.Count >= Capacity) _buffer.Dequeue();
            _buffer.Enqueue(line);
        }
    }

    public IReadOnlyList<string> RecentEvents()
    {
        lock (_lock) return _buffer.ToArray();
    }
}

/// <summary>
/// Porta de br.com.atos.printbridge.util.Logger.event (Android) — logging estruturado (JSON por
/// linha) com contexto de correlação (jobId/printerId), para reconstruir o histórico de um job a
/// partir do arquivo de log/diagnóstico. Nunca inclua PIN, payload ZPL completo ou outros dados
/// sensíveis nos campos de evento — mesma regra do Android.
/// </summary>
public interface IEventLogger
{
    void Event(LogLevel level, string eventName, string? jobId = null, string? printerId = null, object? extra = null);
    IReadOnlyList<string> RecentEvents();
}

public sealed class EventLogger(ILogger<EventLogger> logger, RecentEventsBuffer buffer) : IEventLogger
{
    /// <summary>
    /// Salvaguarda de código, não só de convenção de comentário (CARREFOUR-PRINT-DATA-CLASSIFICATION.md §4):
    /// qualquer propriedade de `extra` cujo nome bata com este padrão tem o VALOR redigido antes
    /// de entrar no ring buffer — ou seja, antes de poder ser exportado por DiagnosticsExporter,
    /// escrito no Windows Event Log, ou aparecer no console. Protege contra um
    /// `extra: new { licenseToken = token }` adicionado por engano no futuro, sem depender de
    /// ninguém lembrar da regra em code review.
    /// </summary>
    private static readonly Regex SensitiveKeyPattern = new(
        "token|senha|password|secret|credential|adminpin|licensetoken|devicetoken|rawtoken",
        RegexOptions.IgnoreCase);

    public void Event(LogLevel level, string eventName, string? jobId = null, string? printerId = null, object? extra = null)
    {
        var fields = new List<(string Key, object? Value)>
        {
            ("ts", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()),
            ("level", level.ToString().ToUpperInvariant()),
            ("event", eventName),
        };
        if (jobId is not null) fields.Add(("jobId", jobId));
        if (printerId is not null) fields.Add(("printerId", printerId));
        if (extra is not null)
        {
            foreach (var prop in extra.GetType().GetProperties())
            {
                var value = SensitiveKeyPattern.IsMatch(prop.Name) ? "[REDACTED]" : prop.GetValue(extra);
                fields.Add((prop.Name, value));
            }
        }

        var line = ToJsonLine(fields);
        buffer.Add(line);

        logger.Log(level, "{EventLine}", line);
    }

    public IReadOnlyList<string> RecentEvents() => buffer.RecentEvents();

    private static string ToJsonLine(List<(string Key, object? Value)> fields)
    {
        var sb = new StringBuilder("{");
        for (var i = 0; i < fields.Count; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append('"').Append(Escape(fields[i].Key)).Append("\":").Append(RenderValue(fields[i].Value));
        }
        sb.Append('}');
        return sb.ToString();
    }

    private static string RenderValue(object? value) => value switch
    {
        null => "null",
        bool b => b ? "true" : "false",
        sbyte or byte or short or ushort or int or uint or long or ulong =>
            Convert.ToString(value, CultureInfo.InvariantCulture)!,
        float or double or decimal =>
            Convert.ToString(value, CultureInfo.InvariantCulture)!,
        _ => $"\"{Escape(value.ToString() ?? "")}\"",
    };

    private static string Escape(string value) => value
        .Replace("\\", "\\\\")
        .Replace("\"", "\\\"")
        .Replace("\n", "\\n");
}
