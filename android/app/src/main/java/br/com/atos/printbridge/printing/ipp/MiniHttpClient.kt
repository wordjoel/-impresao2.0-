package br.com.atos.printbridge.printing.ipp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HTTP/1.1 mínimo sobre socket, só para POST de IPP na rede local. Entende as três formas de
 * resposta que impressoras usam: Content-Length, `Transfer-Encoding: chunked` e corpo até o
 * fechamento da conexão (`Connection: close`).
 */
object MiniHttpClient {

    class HttpProtocolException(message: String) : IOException(message)

    data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun post(
        host: String,
        port: Int,
        path: String,
        contentType: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int,
    ): HttpResponse {
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            val hostHeader = if (port == 80) host else "$host:$port"
            val head = buildString {
                append("POST ").append(path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Accept: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n")
                append("User-Agent: AtosPrintBridge\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return readResponse(BufferedInputStream(socket.getInputStream()), maxResponseBytes)
        }
    }

    /**
     * Requisição genérica (passo 6: GET/POST do coletor ao PC das etiquetas). [post] fica como está
     * para não mudar uma vírgula do que o IPP manda às impressoras. Valores de cabeçalho com CR/LF
     * são recusados (sem injeção de cabeçalho).
     */
    fun request(
        method: String,
        host: String,
        port: Int,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int,
    ): HttpResponse {
        require(method.isNotEmpty() && method.all { it in 'A'..'Z' }) { "método HTTP inválido" }
        require(path.startsWith("/") && path.none { it.code <= 0x20 || it.code >= 0x7F }) { "caminho HTTP inválido" }
        headers.forEach { (k, v) -> require(k.isNotEmpty() && k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "cabeçalho HTTP inválido" } }
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            val head = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(if (port == 80) host else "$host:$port").append("\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n")
                append("User-Agent: AtosPrintBridge\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(BufferedInputStream(socket.getInputStream()), maxResponseBytes)
        }
    }

    /** Parte pura: lê status, cabeçalhos e corpo de um stream HTTP/1.x. Ignora respostas 100-continue. */
    fun readResponse(input: InputStream, maxResponseBytes: Int): HttpResponse {
        while (true) {
            val statusLine = readLine(input) ?: throw HttpProtocolException("conexão fechada antes da resposta")
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw HttpProtocolException("linha de status inválida: '$statusLine'")
            val status = parts[1].toIntOrNull() ?: throw HttpProtocolException("status inválido: '$statusLine'")

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: throw HttpProtocolException("cabeçalhos truncados")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            if (status == 100) continue

            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input, maxResponseBytes)
                headers["content-length"] != null -> {
                    val length = headers["content-length"]!!.toIntOrNull() ?: throw HttpProtocolException("Content-Length inválido")
                    if (length > maxResponseBytes) throw HttpProtocolException("resposta acima de $maxResponseBytes bytes")
                    readExactly(input, length)
                }
                else -> readToEnd(input, maxResponseBytes)
            }
            return HttpResponse(status, headers, body)
        }
    }

    private fun readChunked(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw HttpProtocolException("chunk truncado")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpProtocolException("tamanho de chunk inválido: '$sizeLine'")
            if (size == 0) {
                // Trailers opcionais até a linha vazia.
                while (true) { val t = readLine(input) ?: break; if (t.isEmpty()) break }
                return out.toByteArray()
            }
            if (out.size() + size > max) throw HttpProtocolException("resposta acima de $max bytes")
            out.write(readExactly(input, size))
            readLine(input) // CRLF depois do chunk
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw HttpProtocolException("corpo truncado ($offset de $length bytes)")
            offset += read
        }
        return bytes
    }

    private fun readToEnd(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > max) throw HttpProtocolException("resposta acima de $max bytes")
            out.write(buffer, 0, read)
        }
    }

    /** Linha terminada em CRLF (ou LF), em ASCII. Nulo = fim do stream sem nada lido. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            out.write(b)
            if (out.size() > 8 * 1024) throw HttpProtocolException("linha HTTP longa demais")
        }
    }
}
