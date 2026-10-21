package br.com.atos.printbridge.data.coletor

import br.com.atos.printbridge.application.coletor.ColetorRemote
import br.com.atos.printbridge.domain.coletor.ColetorLink
import br.com.atos.printbridge.domain.coletor.ColetorPrintPolicy.VersionCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Cliente HTTP do coletor contra um servidor de verdade (socket local). O ColetorLink normalmente só
 * aceita IP privado; aqui o host é montado direto para apontar ao loopback do teste.
 */
class ColetorHttpRemoteTest {

    private val server = ServerSocket(0)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var reply: String = ""
    @Volatile private var hang = false

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        val head = StringBuilder()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            head.append(line).append('\n')
                        }
                        requests += head.toString()
                        if (hang) Thread.sleep(4000)
                        it.getOutputStream().write(reply.toByteArray(Charsets.UTF_8))
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }

    @After
    fun close() = server.close()

    private val link = ColetorLink("d-1", "tok".repeat(10), "127.0.0.1", server.localPort, "kid", ByteArray(0))
    private val remote = ColetorHttpRemote()

    private fun respond(status: Int, body: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        reply = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n$body"
    }

    @Test
    fun `conferencia de versao manda o token e le a resposta`() {
        respond(200, """{"templateId":"t1","templateVersion":3,"packageSha256":"${"a".repeat(64)}"}""")
        assertEquals(VersionCheck.Answered(3, "a".repeat(64)), remote.version(link, "t1"))
        val req = requests.single()
        assertTrue(req, req.startsWith("GET /coletor/v1/labels/t1/version HTTP/1.1\n"))
        assertTrue(req.contains("Authorization: Bearer ${"tok".repeat(10)}\n"))
        assertTrue(req.contains("Host: 127.0.0.1:${server.localPort}\n"))
    }

    @Test
    fun `status viram as decisoes do contrato`() {
        respond(404, """{"errorCode":"LABEL_NOT_PUBLISHED"}""")
        assertEquals(VersionCheck.NotPublished, remote.version(link, "t1"))
        respond(401, """{"errorCode":"DEVICE_NOT_AUTHORIZED"}""")
        assertEquals(VersionCheck.Unauthorized, remote.version(link, "t1"))
        respond(500, "{}")
        assertEquals(VersionCheck.Unreachable, remote.version(link, "t1"))
        respond(200, """{"templateVersion":3,"packageSha256":"curto"}""")
        assertEquals(VersionCheck.Unreachable, remote.version(link, "t1"))

        respond(200, """{"packageJson":"{}"}""")
        assertEquals(ColetorRemote.Envelope.Ok("""{"packageJson":"{}"}"""), remote.envelope(link, "t1"))
        respond(404)
        assertEquals(ColetorRemote.Envelope.NotPublished, remote.envelope(link, "t1"))
        respond(401)
        assertEquals(ColetorRemote.Envelope.Unauthorized, remote.envelope(link, "t1"))
    }

    @Test
    fun `PC que nao responde em 3 s vira Unreachable`() {
        hang = true
        respond(200, """{"templateVersion":3,"packageSha256":"${"a".repeat(64)}"}""")
        val start = System.nanoTime()
        assertEquals(VersionCheck.Unreachable, remote.version(link, "t1"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("levou $ms ms", ms < 3000)
    }

    @Test
    fun `porta fechada vira Unreachable`() {
        val closed = ServerSocket(0).run { val p = localPort; close(); p }
        assertEquals(VersionCheck.Unreachable, remote.version(link.copy(port = closed), "t1"))
    }
}
