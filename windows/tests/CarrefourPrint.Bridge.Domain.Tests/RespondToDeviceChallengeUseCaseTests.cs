using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>Testa a orquestração (decodifica/recodifica Base64, repassa bytes) — a garantia criptográfica real é testada contra o provider de verdade em Infrastructure.Tests.</summary>
public class RespondToDeviceChallengeUseCaseTests
{
    [Fact]
    public void Respond_passes_the_decoded_challenge_bytes_to_the_identity_provider()
    {
        var identityProvider = new FakeDeviceIdentityProvider();
        var useCase = new RespondToDeviceChallengeUseCase(identityProvider);
        var challengeBytes = "um-nonce-de-32-bytes-do-servidor"u8.ToArray();
        var challengeBase64 = Convert.ToBase64String(challengeBytes);

        useCase.Respond(challengeBase64);

        Assert.Equal(challengeBytes, identityProvider.LastSignedChallenge);
    }

    [Fact]
    public void Respond_returns_the_signature_base64_encoded()
    {
        var identityProvider = new FakeDeviceIdentityProvider();
        var useCase = new RespondToDeviceChallengeUseCase(identityProvider);
        var challengeBytes = "abc"u8.ToArray();
        var challengeBase64 = Convert.ToBase64String(challengeBytes);

        var responseBase64 = useCase.Respond(challengeBase64);

        var expectedSignature = identityProvider.Sign(challengeBytes); // mesmo fake, mesma lógica determinística
        Assert.Equal(Convert.ToBase64String(expectedSignature), responseBase64);
    }

    [Fact]
    public void Different_challenges_produce_different_responses()
    {
        var identityProvider = new FakeDeviceIdentityProvider();
        var useCase = new RespondToDeviceChallengeUseCase(identityProvider);

        var response1 = useCase.Respond(Convert.ToBase64String("challenge-um"u8.ToArray()));
        var response2 = useCase.Respond(Convert.ToBase64String("challenge-dois"u8.ToArray()));

        Assert.NotEqual(response1, response2);
    }
}
