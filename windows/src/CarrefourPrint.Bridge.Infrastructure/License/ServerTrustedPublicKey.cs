namespace CarrefourPrint.Bridge.Infrastructure.License;

/// <summary>
/// Chave pública do servidor de licenciamento, embutida no binário do app — CARREFOUR-PRINT-SERVER-LICENSING.md §5.
/// Precisa ser fixa em tempo de build (nunca obtida em runtime/rede), senão um MITM poderia
/// substituí-la e assinar tokens falsos com a própria chave do atacante.
///
/// ATENÇÃO: este é o valor de TESTE gerado para o vetor de CARREFOUR-PRINT-SERVER-LICENSING.md
/// §6 — NÃO é uma chave de produção (nenhum backend real existe ainda para ter uma). Antes de
/// qualquer ativação real de cliente, este valor DEVE ser substituído pela chave pública real do
/// backend de licenciamento, e o MESMO valor deve ser usado no app Android correspondente — as
/// duas plataformas verificam contra a mesma chave do mesmo servidor.
/// </summary>
public static class ServerTrustedPublicKey
{
    public const string TestServerPublicKeySpkiBase64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/" +
        "aKBEpivJfg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ==";

    public static byte[] TestServerPublicKeySpki => Convert.FromBase64String(TestServerPublicKeySpkiBase64);
}
