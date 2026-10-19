using System.ComponentModel;
using System.Diagnostics;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.License;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.Configuration;
using CarrefourPrint.Bridge.Infrastructure.License;
using CarrefourPrint.Bridge.Infrastructure.Logging;
using CarrefourPrint.Bridge.Infrastructure.Network;
using CarrefourPrint.Bridge.Infrastructure.Persistence;
using CarrefourPrint.Bridge.Infrastructure.Transport;
using CarrefourPrint.Bridge.Worker;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

// %ProgramData% é o local correto para dados de um serviço Windows rodando como LocalSystem —
// não fica preso ao perfil de um usuário que pode nunca logar na máquina (equivalente ao
// "contexto do app" do Android, que também não depende de sessão de usuário).
var programData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
var dataDirectory = string.IsNullOrEmpty(programData)
    ? AppContext.BaseDirectory
    : Path.Combine(programData, "CarrefourPrint", "Bridge");

var databasePath = Path.Combine(dataDirectory, "bridge.db");
var configurationPath = Path.Combine(dataDirectory, "configuration.json");
var licensePath = Path.Combine(dataDirectory, "license.json");
var diagnosticsDirectory = Path.Combine(dataDirectory, "diagnostics");

// Restringe o diretório a Administradores/SYSTEM antes de qualquer arquivo ser criado nele —
// sem isso, o ACL herdado de %ProgramData% tipicamente permite leitura a qualquer usuário local
// (CARREFOUR-PRINT-DATA-CLASSIFICATION.md §4). Falha aqui não deve impedir o serviço de subir
// (ex.: processo sem privilégio suficiente numa execução interativa de desenvolvimento) — melhor
// operar com o ACL herdado (menos restritivo) do que não operar, mas o evento fica registrado.
try
{
    SecureDataDirectory.Protect(dataDirectory);
}
catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException)
{
    Console.Error.WriteLine($"Aviso: não foi possível restringir o ACL de {dataDirectory}: {ex.Message}");
}

// Limpa .tmp órfãos de uma execução anterior encerrada abruptamente entre a criação do
// temporário e o rename atômico (CARREFOUR-PRINT-WINDOWS-HARDENING.md §3) — best-effort.
if (Directory.Exists(dataDirectory))
{
    foreach (var orphan in Directory.EnumerateFiles(dataDirectory, "*.tmp"))
    {
        try { File.Delete(orphan); } catch { /* best-effort */ }
    }
}

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "CarrefourPrintBridge");

// Registrar uma NOVA fonte de Event Log exige privilégio elevado (confirmado empiricamente
// nesta sessão — CARREFOUR-PRINT-WINDOWS-HARDENING.md §5: falha com "Acesso negado" sob uma
// conta sem privilégio administrativo). Em vez de exigir que o serviço rode elevado só por
// causa disso, o registro da fonte é responsabilidade de um passo de instalação elevado
// (uma única vez — CARREFOUR-PRINT-WINDOWS-HARDENING.md §9). Aqui, se a fonte já existe
// (instalação correta) ela é usada normalmente; se não existe e não há privilégio para criá-la,
// o serviço continua rodando só com o log estruturado em console/ring buffer — nunca falha ao
// iniciar por causa disso.
const string eventLogSourceName = "CarrefourPrintBridge";
try
{
    if (!EventLog.SourceExists(eventLogSourceName))
    {
        EventLog.CreateEventSource(new EventSourceCreationData(eventLogSourceName, "Application"));
    }
    builder.Logging.AddEventLog(settings => settings.SourceName = eventLogSourceName);
}
catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or Win32Exception)
{
    Console.Error.WriteLine(
        $"Aviso: sem privilégio para criar/usar a fonte de Event Log '{eventLogSourceName}' — " +
        $"registre-a durante a instalação (elevada, uma única vez) com " +
        $"'New-EventLog -LogName Application -Source {eventLogSourceName}'. " +
        $"O serviço continua operando normalmente sem Event Log ({ex.GetType().Name}).");
}

var connectionFactory = new SqliteConnectionFactory(databasePath);
connectionFactory.EnsureSchema();
builder.Services.AddSingleton(connectionFactory);

builder.Services.AddSingleton<IPrinterRepository, SqlitePrinterRepository>();
builder.Services.AddSingleton<IPrintJobRepository, SqlitePrintJobRepository>();
builder.Services.AddSingleton<IConfigurationRepository>(_ => new JsonFileConfigurationRepository(configurationPath));
builder.Services.AddSingleton<ILicenseRepository>(_ => new JsonFileLicenseRepository(licensePath));
// TODO(produção): substituir ServerTrustedPublicKey.TestServerPublicKeySpki pela chave pública
// real do backend de licenciamento antes de qualquer ativação de cliente real — ver o aviso em
// ServerTrustedPublicKey.cs e CARREFOUR-PRINT-SERVER-LICENSING.md §5.
builder.Services.AddSingleton<ILicenseTokenVerifier>(_ => new EcdsaLicenseTokenVerifier(ServerTrustedPublicKey.TestServerPublicKeySpki));
builder.Services.AddSingleton<INetworkStateProvider, WindowsNetworkStateProvider>();
builder.Services.AddSingleton<IPrinterTransport, TcpPrinterTransport>();
builder.Services.AddSingleton<IDeviceIdentityProvider, WindowsCngDeviceIdentityProvider>();

builder.Services.AddSingleton<RecentEventsBuffer>();
builder.Services.AddSingleton<IEventLogger, EventLogger>();
builder.Services.AddSingleton(sp => new DiagnosticsExporter(
    sp.GetRequiredService<IEventLogger>(),
    sp.GetRequiredService<IDeviceIdentityProvider>(),
    diagnosticsDirectory,
    typeof(Program).Assembly.GetName().Version?.ToString() ?? "0.1.0"));

builder.Services.AddSingleton<InProcessQueueDispatcher>();
builder.Services.AddSingleton<IQueueDispatcher>(sp => sp.GetRequiredService<InProcessQueueDispatcher>());

builder.Services.AddSingleton<DuplicateSubmissionGuard>();
builder.Services.AddSingleton<PrinterQueueMutexProvider>();
builder.Services.AddSingleton<PrintEngine>();
builder.Services.AddSingleton<EnqueuePrintJobUseCase>();
builder.Services.AddSingleton<ProcessPrintQueueUseCase>();
builder.Services.AddSingleton<RecoverOrphanedJobsUseCase>();
builder.Services.AddSingleton<PurgeExpiredHistoryUseCase>();
builder.Services.AddSingleton<ReprintJobUseCase>();
builder.Services.AddSingleton<CancelPrintJobUseCase>();
builder.Services.AddSingleton<RetryPrintJobUseCase>();
builder.Services.AddSingleton<EvaluateLicenseStatusUseCase>();
builder.Services.AddSingleton<StoreLicenseTokenUseCase>();
builder.Services.AddSingleton<RespondToDeviceChallengeUseCase>();

builder.Services.AddHostedService<QueueProcessingWorker>();

var host = builder.Build();
host.Run();

// Necessário para o WebApplicationFactory-like pattern de teste de integração (xUnit) enxergar
// o Program implícito como classe parcial pública.
public partial class Program;
