using System.Net.NetworkInformation;
using System.Runtime.Versioning;
using CarrefourPrint.Bridge.Domain.Abstractions;

namespace CarrefourPrint.Bridge.Infrastructure.Network;

/// <summary>Porta de br.com.atos.printbridge.network.AndroidNetworkStateProvider (Android) para a API de rede do .NET/Windows.</summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsNetworkStateProvider : INetworkStateProvider
{
    public bool IsNetworkAvailable()
    {
        if (!NetworkInterface.GetIsNetworkAvailable()) return false;

        return NetworkInterface.GetAllNetworkInterfaces().Any(nic =>
            nic.OperationalStatus == OperationalStatus.Up &&
            nic.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 &&
            nic.GetIPProperties().UnicastAddresses.Any(a => a.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork));
    }
}
