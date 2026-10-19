package br.com.atos.printbridge.printing.ipp

import br.com.atos.printbridge.domain.ipp.IppCodec
import br.com.atos.printbridge.domain.ipp.IppEndpoint
import br.com.atos.printbridge.domain.ipp.IppFailureKind
import br.com.atos.printbridge.domain.ipp.IppGateway
import br.com.atos.printbridge.domain.ipp.IppJobAccepted
import br.com.atos.printbridge.domain.ipp.IppPrinterDescription
import br.com.atos.printbridge.domain.ipp.IppResult
import br.com.atos.printbridge.util.LogLevel
import br.com.atos.printbridge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * IPP sobre HTTP (porta anunciada pela impressora, normalmente 631), por socket direto ([MiniHttpClient]):
 * o network_security_config proíbe cleartext na pilha HTTP do Android para a NUVEM (Trecho A), e o
 * tráfego LAN com a impressora é o Trecho B — o mesmo caminho do RAW 9100 (CARREFOUR-PRINT-TRAFFIC-MAP.md). IPPS (TLS) não entra
 * aqui: impressoras de rede usam certificado autoassinado e aceitar qualquer certificado seria
 * desligar a verificação — o serviço IPPS aparece na descoberta como "não suportado".
 */
class HttpIppGateway @Inject constructor() : IppGateway {

    private val requestIds = AtomicInteger(1)

    override suspend fun getPrinterAttributes(endpoint: IppEndpoint, timeoutMillis: Int): IppResult<IppPrinterDescription> {
        val body = IppCodec.encodeRequest(
            IppCodec.OP_GET_PRINTER_ATTRIBUTES,
            requestIds.getAndIncrement(),
            listOf(
                IppCodec.operationGroup(
                    endpoint.uri,
                    IppCodec.Attr(IppCodec.TAG_KEYWORD, "requested-attributes", IppPrinterDescription.REQUESTED_ATTRIBUTES),
                ),
            ),
        )
        return when (val result = post(endpoint, body, timeoutMillis)) {
            is IppResult.Failure -> result
            is IppResult.Ok -> {
                val response = result.value
                if (!response.isSuccess) {
                    IppResult.Failure(IppFailureKind.IPP_ERROR, "Get-Printer-Attributes recusado: ${IppCodec.statusName(response.statusCode)}")
                } else {
                    IppResult.Ok(IppPrinterDescription.from(response))
                }
            }
        }
    }

    override suspend fun printJob(
        endpoint: IppEndpoint,
        documentFormat: String,
        jobName: String,
        copies: Int,
        document: ByteArray,
        timeoutMillis: Int,
    ): IppResult<IppJobAccepted> {
        val header = IppCodec.encodeRequest(
            IppCodec.OP_PRINT_JOB,
            requestIds.getAndIncrement(),
            buildList {
                add(
                    IppCodec.operationGroup(
                        endpoint.uri,
                        IppCodec.Attr(IppCodec.TAG_NAME, "job-name", jobName.take(255)),
                        IppCodec.Attr(IppCodec.TAG_MIME, "document-format", documentFormat),
                    ),
                )
                if (copies > 1) add(IppCodec.Group(IppCodec.TAG_JOB, listOf(IppCodec.Attr(IppCodec.TAG_INTEGER, "copies", copies))))
            },
        )
        return when (val result = post(endpoint, header + document, timeoutMillis)) {
            is IppResult.Failure -> result
            is IppResult.Ok -> {
                val response = result.value
                if (!response.isSuccess) {
                    val detail = response.string("status-message")?.let { " ($it)" } ?: ""
                    IppResult.Failure(IppFailureKind.IPP_ERROR, "Print-Job recusado: ${IppCodec.statusName(response.statusCode)}$detail")
                } else {
                    IppResult.Ok(IppJobAccepted(response.int("job-id"), response.statusCode))
                }
            }
        }
    }

    private suspend fun post(endpoint: IppEndpoint, body: ByteArray, timeoutMillis: Int): IppResult<IppCodec.Response> =
        withContext(Dispatchers.IO) {
            try {
                val http = MiniHttpClient.post(
                    host = endpoint.host,
                    port = endpoint.port,
                    path = "/" + endpoint.normalizedPath,
                    contentType = "application/ipp",
                    body = body,
                    connectTimeoutMillis = timeoutMillis,
                    readTimeoutMillis = timeoutMillis.coerceAtLeast(READ_TIMEOUT_FLOOR_MILLIS),
                    maxResponseBytes = MAX_RESPONSE_BYTES,
                )
                if (http.status != 200) {
                    return@withContext IppResult.Failure(IppFailureKind.NOT_IPP, "HTTP ${http.status} em ${endpoint.httpUrl}")
                }
                val contentType = http.header("content-type").orEmpty()
                if (!contentType.startsWith("application/ipp", ignoreCase = true)) {
                    return@withContext IppResult.Failure(IppFailureKind.NOT_IPP, "resposta não é IPP (Content-Type '$contentType')")
                }
                IppResult.Ok(IppCodec.decodeResponse(http.body))
            } catch (e: SocketTimeoutException) {
                fail(endpoint, IppFailureKind.TIMEOUT, "tempo esgotado (${timeoutMillis} ms)")
            } catch (e: ConnectException) {
                fail(endpoint, IppFailureKind.UNREACHABLE, e.message ?: "conexão recusada")
            } catch (e: NoRouteToHostException) {
                fail(endpoint, IppFailureKind.UNREACHABLE, e.message ?: "sem rota até o host")
            } catch (e: IppCodec.MalformedResponseException) {
                fail(endpoint, IppFailureKind.NOT_IPP, "resposta IPP inválida: ${e.message}")
            } catch (e: MiniHttpClient.HttpProtocolException) {
                fail(endpoint, IppFailureKind.NOT_IPP, "resposta HTTP inválida: ${e.message}")
            } catch (e: IOException) {
                fail(endpoint, IppFailureKind.UNREACHABLE, e.message ?: e::class.simpleName.orEmpty())
            }
        }

    private fun fail(endpoint: IppEndpoint, kind: IppFailureKind, message: String): IppResult.Failure {
        Logger.event(LogLevel.WARN, "ipp_call_failed", extra = mapOf("endpoint" to endpoint.uri, "kind" to kind.name, "detail" to message))
        return IppResult.Failure(kind, message)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        /** Print-Job pode demorar a responder enquanto a impressora recebe o documento. */
        const val READ_TIMEOUT_FLOOR_MILLIS = 5_000
    }
}
