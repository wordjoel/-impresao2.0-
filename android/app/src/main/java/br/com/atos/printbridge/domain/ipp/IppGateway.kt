package br.com.atos.printbridge.domain.ipp

/** Endereço de um serviço IPP: `ipp://host:porta/caminho`. O caminho vem do mDNS (`rp`) ou da sondagem. */
data class IppEndpoint(val host: String, val port: Int, val path: String) {
    val normalizedPath: String get() = path.trim().trimStart('/')
    val uri: String get() = "ipp://$host:$port/$normalizedPath"
    val httpUrl: String get() = "http://$host:$port/$normalizedPath"
}

/** Por que uma chamada IPP falhou — cada caso vira uma mensagem diferente e honesta na tela. */
enum class IppFailureKind {
    /** TCP não conectou (desligada, fora da rede, porta fechada). */
    UNREACHABLE,
    /** Conectou mas demorou além do timeout. */
    TIMEOUT,
    /** Algo respondeu na porta, mas não é IPP (HTTP de erro, HTML, lixo). */
    NOT_IPP,
    /** É IPP, mas a impressora recusou a operação (status IPP de erro). */
    IPP_ERROR,
}

sealed class IppResult<out T> {
    data class Ok<T>(val value: T) : IppResult<T>()
    data class Failure(val kind: IppFailureKind, val technicalMessage: String) : IppResult<Nothing>()
}

/** Resultado aceito de um Print-Job: o id que a impressora deu ao trabalho. */
data class IppJobAccepted(val jobId: Int?, val statusCode: Int)

interface IppGateway {
    suspend fun getPrinterAttributes(endpoint: IppEndpoint, timeoutMillis: Int): IppResult<IppPrinterDescription>

    suspend fun printJob(
        endpoint: IppEndpoint,
        documentFormat: String,
        jobName: String,
        copies: Int,
        document: ByteArray,
        timeoutMillis: Int,
    ): IppResult<IppJobAccepted>

    companion object {
        /**
         * Caminhos testados quando o mDNS não informou o `rp`. Não é "chute de porta": a porta já
         * respondeu, e só se aceita o caminho em que a impressora devolve uma resposta IPP válida.
         * `ipp/print` é o do IPP Everywhere/Mopria; `ipp` e raiz cobrem firmwares mais antigos.
         */
        val CANDIDATE_PATHS = listOf("ipp/print", "ipp", "")
    }
}
