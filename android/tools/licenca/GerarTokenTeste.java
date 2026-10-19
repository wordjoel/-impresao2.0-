import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Gera um License Token de HOMOLOGAÇÃO/LABORATÓRIO para o A.print.Bridge, assinado com a chave
 * privada de TESTE (publicada em CARREFOUR-PRINT-SERVER-LICENSING.md §6 — não é segredo e NUNCA
 * vale em produção: o gate C2 do build impede release com a chave pública de teste).
 *
 * Uso (JDK 17+, sem compilar):
 *   java tools/licenca/GerarTokenTeste.java                 -> 30 dias, deviceId "lab"
 *   java tools/licenca/GerarTokenTeste.java 90 <deviceId>   -> 90 dias, deviceId do coletor
 *
 * Formato: base64url("deviceId|licenseId|tenantId|ACTIVE|issuedAt|validUntil|maxOfflineMillis")
 *          + "." + base64url(assinatura DER SHA256withECDSA) — o mesmo do EcdsaLicenseTokenVerifier.
 */
public class GerarTokenTeste {

    // PKCS#8 da chave EC P-256 de TESTE (mesma do TestLicenseTokenSigner dos testes unitários).
    private static final String TEST_PRIVATE_KEY_PKCS8 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgyAA9rC7g3uznowGagIDl+GjfSLOj8eg8ogZXPgFt+EChRANCAARb72zP7LmVJV2IpuUcNOn3Da3inf86IYYo8v3ShRhNCn9ooESmK8l+DrnFqTX/G0jttcewT9eYNs4TvdWkaTV1";

    public static void main(String[] args) throws Exception {
        int days = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        String deviceId = args.length > 1 ? args[1] : "lab";
        long now = System.currentTimeMillis();
        long validUntil = now + days * 24L * 60 * 60 * 1000;
        long maxOffline = 72L * 60 * 60 * 1000;

        String canonical = String.join("|",
            deviceId, "lic-homologacao", "tenant-homologacao", "ACTIVE",
            Long.toString(now), Long.toString(validUntil), Long.toString(maxOffline));
        byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);

        PrivateKey key = KeyFactory.getInstance("EC")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(TEST_PRIVATE_KEY_PKCS8)));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(payload);
        byte[] signature = signer.sign();

        Base64.Encoder b64url = Base64.getUrlEncoder().withoutPadding();
        System.out.println(b64url.encodeToString(payload) + "." + b64url.encodeToString(signature));
    }
}
