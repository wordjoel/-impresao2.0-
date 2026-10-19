using System.Security.Cryptography;
using CarrefourPrint.Bridge.Domain.Abstractions;
using CarrefourPrint.Bridge.Domain.Models;
using CarrefourPrint.Bridge.Domain.Tests.Fakes;
using CarrefourPrint.Bridge.Domain.UseCases;

namespace CarrefourPrint.Bridge.Domain.Tests;

/// <summary>
/// Reescrita para o modelo de License Token assinado — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
/// Usa o verificador REAL (EcdsaLicenseTokenVerifier) contra a chave pública de teste, e o
/// assinador real (TestLicenseTokenSigner) contra a chave privada de teste correspondente — não
/// fakes, porque o próprio propósito desta funcionalidade é a assinatura criptográfica real.
/// </summary>
public class LicenseUseCasesTests
{
    private static readonly ILicenseTokenVerifier Verifier = TestLicenseTokenSigner.CreateVerifier();

    [Fact]
    public void Not_activated_device_is_reported_as_not_activated_regardless_of_time()
    {
        var state = new LicenseState { RawToken = null };
        Assert.Equal(EffectiveLicenseStatus.NotActivated, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 0));
    }

    [Fact]
    public void Active_token_within_validity_window_is_operating()
    {
        var token = TestLicenseTokenSigner.Sign(status: LicenseTokenStatus.Active, issuedAt: 1000, validUntil: 2000);
        var state = new LicenseState { RawToken = token };

        Assert.Equal(EffectiveLicenseStatus.Operating, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 1500));
    }

    [Fact]
    public void Active_token_past_its_validUntil_is_restricted()
    {
        // A tolerância offline agora é decidida pelo servidor via validUntil, não por uma
        // constante do cliente — este teste prova exatamente esse limite.
        var token = TestLicenseTokenSigner.Sign(status: LicenseTokenStatus.Active, issuedAt: 1000, validUntil: 2000);
        var state = new LicenseState { RawToken = token };

        Assert.Equal(EffectiveLicenseStatus.Restricted, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 2001));
    }

    [Theory]
    [InlineData(LicenseTokenStatus.Pending)]
    [InlineData(LicenseTokenStatus.Suspended)]
    [InlineData(LicenseTokenStatus.Expired)]
    [InlineData(LicenseTokenStatus.Revoked)]
    public void Any_non_active_token_status_is_restricted_even_within_the_validity_window(LicenseTokenStatus status)
    {
        var token = TestLicenseTokenSigner.Sign(status: status, issuedAt: 1000, validUntil: 999_999_999);
        var state = new LicenseState { RawToken = token };

        Assert.Equal(EffectiveLicenseStatus.Restricted, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 1500));
    }

    [Fact]
    public void Tampered_payload_is_restricted_not_trusted_as_active()
    {
        // Prova a regra central do pedido: nada armazenado localmente é confiável por si só.
        // Aqui simulamos alguém editando o arquivo local para tentar forjar status=ACTIVE — a
        // assinatura, calculada sobre o payload original, deixa de bater com o payload adulterado.
        var validToken = TestLicenseTokenSigner.Sign(status: LicenseTokenStatus.Suspended, issuedAt: 1000, validUntil: 999_999_999);
        var parts = validToken.Split('.');
        var forgedPayload = Convert.ToBase64String("test-device|lic-test|tenant-test|ACTIVE|1000|999999999|259200000"u8.ToArray())
            .Replace('+', '-').Replace('/', '_').TrimEnd('=');
        var tamperedToken = $"{forgedPayload}.{parts[1]}"; // assinatura antiga, payload novo forjado

        var state = new LicenseState { RawToken = tamperedToken };

        Assert.Equal(EffectiveLicenseStatus.Restricted, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 1500));
    }

    [Fact]
    public void Malformed_token_is_restricted()
    {
        var state = new LicenseState { RawToken = "isto-nao-e-um-token-valido" };
        Assert.Equal(EffectiveLicenseStatus.Restricted, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 1500));
    }

    [Fact]
    public void Token_signed_by_a_different_key_is_restricted()
    {
        // Simula um token forjado por alguém que não é o servidor real — assina com QUALQUER
        // outra chave, nunca a de teste embutida no verificador.
        using var otherKey = ECDsa.Create();
        var payloadBytes = "test-device|lic-test|tenant-test|ACTIVE|1000|999999999|259200000"u8.ToArray();
        var signature = otherKey.SignData(payloadBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        var forgedToken = $"{Convert.ToBase64String(payloadBytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')}." +
                           $"{Convert.ToBase64String(signature).Replace('+', '-').Replace('/', '_').TrimEnd('=')}";

        var state = new LicenseState { RawToken = forgedToken };

        Assert.Equal(EffectiveLicenseStatus.Restricted, EvaluateLicenseStatusUseCase.Evaluate(state, Verifier, nowMillis: 1500));
    }

    [Fact]
    public async Task Invoke_reads_current_state_from_repository()
    {
        var token = TestLicenseTokenSigner.Sign(status: LicenseTokenStatus.Active, issuedAt: 100, validUntil: 200);
        var repository = new FakeLicenseRepository(new LicenseState { RawToken = token });
        var evaluate = new EvaluateLicenseStatusUseCase(repository, Verifier);

        Assert.Equal(EffectiveLicenseStatus.Operating, await evaluate.InvokeAsync(nowMillis: 150));
    }

    [Fact]
    public async Task Store_license_token_use_case_persists_the_new_token()
    {
        var repository = new FakeLicenseRepository();
        var token = TestLicenseTokenSigner.Sign();

        await new StoreLicenseTokenUseCase(repository).InvokeAsync(token);

        Assert.Equal(token, (await repository.CurrentAsync()).RawToken);
    }
}
