namespace CarrefourPrint.Backend.Reference.Tests;

/// <summary>
/// Fecha, com concorrência real, duas das treze cenas pedidas nesta revisão: "duplicate
/// activation" e "exceeded device limit" — o exemplo de cinco seats de
/// `CARREFOUR-PRINT-SERVER-LICENSING.md` §1, agora executável.
/// </summary>
public class DeviceActivationSecurityTests
{
    [Fact]
    public void First_activation_succeeds_and_consumes_a_seat()
    {
        var service = new DeviceActivationService(TimeProvider.System);

        var result = service.Activate("tenant-a", "license-1", "device-1", maxDevices: 5);

        Assert.Equal(ActivationResult.Activated, result);
        Assert.Equal(1, service.CurrentSeatCount("license-1"));
    }

    [Fact]
    public void Duplicate_activation_of_the_same_device_never_consumes_a_second_seat()
    {
        var service = new DeviceActivationService(TimeProvider.System);
        service.Activate("tenant-a", "license-1", "device-1", maxDevices: 5);

        // Retry — ex.: o cliente reenvia a mesma chamada de ativação porque não recebeu a
        // resposta da primeira a tempo (timeout de rede, cenário comum, não hipotético).
        var second = service.Activate("tenant-a", "license-1", "device-1", maxDevices: 5);

        Assert.Equal(ActivationResult.AlreadyActivated, second);
        Assert.Equal(1, service.CurrentSeatCount("license-1"));
    }

    [Fact]
    public void Sixth_device_is_rejected_when_the_license_allows_only_five()
    {
        var service = new DeviceActivationService(TimeProvider.System);
        for (var i = 1; i <= 5; i++)
        {
            Assert.Equal(ActivationResult.Activated, service.Activate("tenant-a", "license-1", $"device-{i}", maxDevices: 5));
        }

        var sixth = service.Activate("tenant-a", "license-1", "device-6", maxDevices: 5);

        Assert.Equal(ActivationResult.SeatsExhausted, sixth);
        Assert.Equal(5, service.CurrentSeatCount("license-1"));
    }

    [Fact]
    public async Task Concurrent_activations_beyond_the_limit_never_exceed_maxDevices_seats()
    {
        var service = new DeviceActivationService(TimeProvider.System);
        const int maxDevices = 5;
        const int attempts = 50;

        var results = await Task.WhenAll(Enumerable.Range(0, attempts)
            .Select(i => Task.Run(() => service.Activate("tenant-a", "license-1", $"device-{i}", maxDevices))));

        Assert.Equal(maxDevices, results.Count(r => r == ActivationResult.Activated));
        Assert.Equal(attempts - maxDevices, results.Count(r => r == ActivationResult.SeatsExhausted));
        Assert.Equal(maxDevices, service.CurrentSeatCount("license-1"));
    }

    [Fact]
    public async Task Concurrent_duplicate_activation_attempts_for_the_same_device_never_consume_more_than_one_seat()
    {
        // Mesma classe de corrida encontrada de verdade em PrintQueue.cs nesta sessão (reserva e
        // publicação não-atômicas): N chamadas concorrentes para o MESMO device+license devem
        // resultar em exatamente 1 seat consumido, nunca N.
        var service = new DeviceActivationService(TimeProvider.System);

        var results = await Task.WhenAll(Enumerable.Range(0, 32)
            .Select(_ => Task.Run(() => service.Activate("tenant-a", "license-1", "device-1", maxDevices: 5))));

        Assert.Single(results, r => r == ActivationResult.Activated);
        Assert.All(results.Where(r => r != ActivationResult.Activated), r => Assert.Equal(ActivationResult.AlreadyActivated, r));
        Assert.Equal(1, service.CurrentSeatCount("license-1"));
    }

    [Fact]
    public void Different_licenses_have_independent_seat_counts()
    {
        var service = new DeviceActivationService(TimeProvider.System);
        service.Activate("tenant-a", "license-1", "device-1", maxDevices: 1);

        var forDifferentLicense = service.Activate("tenant-a", "license-2", "device-2", maxDevices: 1);

        Assert.Equal(ActivationResult.Activated, forDifferentLicense);
    }
}
