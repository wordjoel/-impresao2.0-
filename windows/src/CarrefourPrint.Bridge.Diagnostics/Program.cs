using CarrefourPrint.Bridge.Diagnostics;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.UseCases;
using CarrefourPrint.Bridge.Infrastructure.Configuration;
using CarrefourPrint.Bridge.Infrastructure.License;
using CarrefourPrint.Bridge.Infrastructure.Logging;
using CarrefourPrint.Bridge.Infrastructure.Network;
using CarrefourPrint.Bridge.Infrastructure.Transport;
using Microsoft.Extensions.Logging;

// Usa o MESMO diretório de dados do Worker Service (%ProgramData%\CarrefourPrint\Bridge) — o
// diagnóstico testa a configuração e a identidade de dispositivo REAIS deste coletor, não um
// cenário sintético à parte. Ver CARREFOUR-PRINT-WINDOWS-BRIDGE-V1.md.
var programData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
var dataDirectory = string.IsNullOrEmpty(programData)
    ? AppContext.BaseDirectory
    : Path.Combine(programData, "CarrefourPrint", "Bridge");
var reportsDirectory = Path.Combine(dataDirectory, "diagnostics", "printer-reports");

// Idempotente com a mesma chamada em CarrefourPrint.Bridge.Worker/Program.cs — cobre o caso do
// diagnóstico rodar antes de o serviço ter sido iniciado alguma vez nesta máquina.
try
{
    SecureDataDirectory.Protect(dataDirectory);
}
catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException)
{
    Console.Error.WriteLine($"Aviso: não foi possível restringir o ACL de {dataDirectory}: {ex.Message}");
}

using var loggerFactory = LoggerFactory.Create(builder => builder.AddSimpleConsole(o =>
{
    o.SingleLine = true;
    o.TimestampFormat = "HH:mm:ss ";
}));

var configurationRepository = new JsonFileConfigurationRepository(Path.Combine(dataDirectory, "configuration.json"));
var networkStateProvider = new WindowsNetworkStateProvider();
var eventLogger = new EventLogger(loggerFactory.CreateLogger<EventLogger>(), new RecentEventsBuffer());
var transport = new TcpPrinterTransport(configurationRepository, networkStateProvider, eventLogger);
var connectionTester = new TcpPrinterConnectionTester(configurationRepository, networkStateProvider);
var diagnosticService = new PrinterDiagnosticService(connectionTester, transport);

using var deviceIdentityProvider = new WindowsCngDeviceIdentityProvider();
var reportExporter = new PrinterDiagnosticReportExporter(deviceIdentityProvider, reportsDirectory);

var options = CliOptions.Parse(args);
if (options.ShowHelp)
{
    CliOptions.PrintUsage();
    return 0;
}

var (ipAddress, port, model, payload, exportReport) = options.IsNonInteractive
    ? options.ToRunParameters()
    : InteractivePrompt.Collect();

Console.WriteLine();
Console.WriteLine("=== Carrefour Print Bridge (Windows) — Modo de Diagnóstico ===");
Console.WriteLine($"Device ID deste coletor: {deviceIdentityProvider.DeviceId()}");
Console.WriteLine($"Testando {ipAddress}:{port} ({DescribeModel(model)})...");
Console.WriteLine();

var result = await diagnosticService.RunAsync(ipAddress, port, model, payload);

DiagnosticConsoleReport.Print(result);

if (exportReport)
{
    var path = reportExporter.Export(result);
    Console.WriteLine();
    Console.WriteLine($"Relatório exportado para: {path}");
}

return result.SendOutcome == DiagnosticSendOutcome.Sent ? 0 : 1;

static string DescribeModel(PrinterModel model) => model switch
{
    PrinterModel.TscMl241 => "TSC ML241",
    PrinterModel.TscMb241 => "TSC MB241",
    PrinterModel.OtherTsc => "Outra TSC",
    _ => "Genérica ZPL",
};
