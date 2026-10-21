package br.com.atos.printbridge.data.coletor

import br.com.atos.printbridge.application.coletor.ColetorRemote
import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import br.com.atos.printbridge.domain.coletor.StrictJson
import br.com.atos.printbridge.printing.ipp.MiniHttpClient
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP do coletor para o PC das etiquetas (rede da loja, porta 7172). Usa o [MiniHttpClient] sobre
 * socket, o mesmo caminho do IPP para impressoras da LAN: o network_security_config (que proíbe
 * cleartext para a nuvem) não alcança socket e não precisa ser afrouxado. A proteção do v1 (P2) é:
 * só IP privado da loja ([ColetorLink.parseServerUrl]), pacote assinado, token só de leitura.
 * O token nunca vai para log.
 */
class ColetorHttpRemote @Inject constructor() : ColetorRemote {

    override fun version(link: ColetorLink, templateId: String): VersionCheck {
        val res = get(link, "/coletor/v1/labels/$templateId/version", VERSION_CONNECT_MS, VERSION_READ_MS, 16 * 1024)
            ?: return VersionCheck.Unreachable
        return when (res.status) {
            200 -> parseVersion(res.body) ?: VersionCheck.Unreachable
            404 -> VersionCheck.NotPublished
            401 -> VersionCheck.Unauthorized
            else -> VersionCheck.Unreachable
        }
    }

    override fun envelope(link: ColetorLink, templateId: String): ColetorRemote.Envelope {
        val res = get(link, "/coletor/v1/labels/$templateId", PACKAGE_CONNECT_MS, PACKAGE_READ_MS, MAX_PACKAGE_BYTES)
            ?: return ColetorRemote.Envelope.Unreachable
        return when (res.status) {
            200 -> ColetorRemote.Envelope.Ok(res.body.toString(Charsets.UTF_8))
            404 -> ColetorRemote.Envelope.NotPublished
            401 -> ColetorRemote.Envelope.Unauthorized
            else -> ColetorRemote.Envelope.Unreachable
        }
    }

    private fun get(link: ColetorLink, path: String, connectMs: Int, readMs: Int, max: Int): MiniHttpClient.HttpResponse? =
        try {
            MiniHttpClient.request(
                method = "GET",
                host = link.host,
                port = link.port,
                path = path,
                headers = mapOf("Authorization" to "Bearer ${link.token}", "Accept" to "application/json"),
                body = null,
                connectTimeoutMillis = connectMs,
                readTimeoutMillis = readMs,
                maxResponseBytes = max,
            )
        } catch (e: IOException) {
            Logger.event(LogLevel.WARN, "coletor_http_unreachable", extra = mapOf("path" to path, "error" to (e.message ?: e::class.simpleName)))
            null
        }

    companion object {
        // Contrato: "sem resposta em 3 s" na conferência de versão (conexão + leitura).
        const val VERSION_CONNECT_MS = 1500
        const val VERSION_READ_MS = 1500
        const val PACKAGE_CONNECT_MS = 3000
        const val PACKAGE_READ_MS = 10_000
        /** Pacote com BITMAP grande em 4 alvos ainda cabe com folga. */
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024

        fun parseVersion(body: ByteArray): VersionCheck.Answered? {
            val o = runCatching { StrictJson.parse(body.toString(Charsets.UTF_8)) as? Map<*, *> }.getOrNull() ?: return null
            val version = (o["templateVersion"] as? Long)?.toInt() ?: return null
            val sha = (o["packageSha256"] as? String)?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return null
            return VersionCheck.Answered(version, sha)
        }
    }
}
