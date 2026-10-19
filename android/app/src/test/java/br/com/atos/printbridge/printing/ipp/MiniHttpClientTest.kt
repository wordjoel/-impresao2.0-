package br.com.atos.printbridge.printing.ipp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class MiniHttpClientTest {

    private fun parse(raw: String) = MiniHttpClient.readResponse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), 1024)

    @Test
    fun `content length body`() {
        val r = parse("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 3\r\n\r\nabcEXTRA")
        assertEquals(200, r.status)
        assertEquals("application/ipp", r.header("Content-Type"))
        assertArrayEquals("abc".toByteArray(), r.body)
    }

    @Test
    fun `chunked body with extension and trailer`() {
        val r = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;x=1\r\nabc\r\n2\r\nde\r\n0\r\nX-T: 1\r\n\r\n")
        assertArrayEquals("abcde".toByteArray(), r.body)
    }

    @Test
    fun `100 continue is skipped and body until close is read`() {
        val r = parse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.0 404 Not Found\r\n\r\nnada aqui")
        assertEquals(404, r.status)
        assertArrayEquals("nada aqui".toByteArray(), r.body)
    }

    @Test
    fun `garbage and oversized responses are refused`() {
        assertTrue(runCatching { parse("SSH-2.0-OpenSSH\r\n") }.exceptionOrNull() is MiniHttpClient.HttpProtocolException)
        assertTrue(runCatching { parse("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n") }.exceptionOrNull() is MiniHttpClient.HttpProtocolException)
    }

    @Test
    fun `post sends host length and body over a real socket`() {
        ServerSocket(0).use { server ->
            var received = ""
            val t = thread {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val buf = ByteArray(4096)
                    val n = input.read(buf)
                    received = String(buf, 0, n, Charsets.ISO_8859_1)
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                }
            }
            val r = MiniHttpClient.post("127.0.0.1", server.localPort, "/ipp/print", "application/ipp", byteArrayOf(1, 2, 3), 2000, 2000, 1024)
            t.join(2000)
            assertEquals(200, r.status)
            assertArrayEquals("ok".toByteArray(), r.body)
            assertTrue(received.startsWith("POST /ipp/print HTTP/1.1\r\n"))
            assertTrue(received.contains("Host: 127.0.0.1:${server.localPort}\r\n"))
            assertTrue(received.contains("Content-Length: 3\r\n"))
        }
    }
}
