namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>Fecha "unauthorized printer" — a treze cenas pedida nesta revisão.</summary>
public class PrinterAuthorizationTests
{
    [Fact]
    public void Device_can_print_to_an_authorized_printer()
    {
        var registry = new AuthorizedPrinterRegistry();
        registry.Authorize("device-1", ["printer-a", "printer-b"]);

        Assert.True(registry.IsAuthorized("device-1", "printer-a"));
    }

    [Fact]
    public void Device_is_denied_for_a_printer_outside_its_authorized_set()
    {
        var registry = new AuthorizedPrinterRegistry();
        registry.Authorize("device-1", ["printer-a"]);

        Assert.False(registry.IsAuthorized("device-1", "printer-c"));
    }

    [Fact]
    public void Unknown_device_is_denied_for_any_printer_fail_closed()
    {
        var registry = new AuthorizedPrinterRegistry();

        Assert.False(registry.IsAuthorized("device-never-registered", "printer-a"));
    }

    [Fact]
    public void A_printer_authorized_for_one_device_is_never_authorized_for_another()
    {
        var registry = new AuthorizedPrinterRegistry();
        registry.Authorize("device-1", ["printer-a"]);
        registry.Authorize("device-2", ["printer-b"]);

        Assert.False(registry.IsAuthorized("device-2", "printer-a"));
    }
}
