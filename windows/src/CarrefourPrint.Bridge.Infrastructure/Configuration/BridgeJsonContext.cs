using System.Text.Json.Serialization;
using CarrefourPrint.Bridge.Domain.Models;

namespace CarrefourPrint.Bridge.Infrastructure.Configuration;

/// <summary>
/// JSON source-generated (sem reflexão) — achado real ao avaliar Native AOT para
/// REVERSE-ENGINEERING-THREAT-MODEL.md §3: `JsonSerializer.SerializeAsync&lt;T&gt;` genérico
/// emite os avisos IL2026/IL3050 do trimmer/AOT ("requer código não referenciável
/// estaticamente"), porque decide em runtime, via reflexão, quais propriedades serializar. Um
/// `JsonSerializerContext` gerado em tempo de compilação elimina essa reflexão inteiramente —
/// pré-requisito real para uma publicação Native AOT funcionar (não só compilar), independente
/// de essa publicação ter sido validada de ponta a ponta nesta sessão (ver §3 do documento acima
/// sobre a etapa de linkedição nativa não verificada por falta de toolchain C++ neste ambiente).
/// </summary>
[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(AppConfiguration))]
[JsonSerializable(typeof(LicenseState))]
internal partial class BridgeJsonContext : JsonSerializerContext;
