using CarrefourPrint.Bridge.Domain.Validation;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>Porta 1:1 de NetworkAddressValidatorTest.kt (Android) — mesmos casos, mesmas asserções.</summary>
public class NetworkAddressValidatorTests
{
    [Fact]
    public void Valid_ipv4_addresses_are_accepted()
    {
        Assert.True(NetworkAddressValidator.IsValidAddress("192.168.0.50"));
        Assert.True(NetworkAddressValidator.IsValidAddress("10.0.0.1"));
        Assert.True(NetworkAddressValidator.IsValidAddress("255.255.255.255"));
    }

    [Fact]
    public void Invalid_ipv4_addresses_are_rejected()
    {
        Assert.False(NetworkAddressValidator.IsValidAddress("999.999.1.1"));
        Assert.False(NetworkAddressValidator.IsValidAddress("256.1.1.1"));
        Assert.False(NetworkAddressValidator.IsValidAddress(""));
        Assert.False(NetworkAddressValidator.IsValidAddress("   "));
    }

    [Fact]
    public void Valid_hostnames_are_accepted()
    {
        Assert.True(NetworkAddressValidator.IsValidAddress("printer-expedicao-01"));
        Assert.True(NetworkAddressValidator.IsValidAddress("printer.local"));
    }

    [Fact]
    public void Valid_ports_are_within_1_to_65535()
    {
        Assert.True(NetworkAddressValidator.IsValidPort(1));
        Assert.True(NetworkAddressValidator.IsValidPort(9100));
        Assert.True(NetworkAddressValidator.IsValidPort(65535));
    }

    [Fact]
    public void Invalid_ports_are_outside_range()
    {
        Assert.False(NetworkAddressValidator.IsValidPort(0));
        Assert.False(NetworkAddressValidator.IsValidPort(-1));
        Assert.False(NetworkAddressValidator.IsValidPort(65536));
    }
}
