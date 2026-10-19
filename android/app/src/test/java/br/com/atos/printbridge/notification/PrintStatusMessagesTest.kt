package br.com.atos.printbridge.notification

import br.com.atos.printbridge.domain.queue.PrintJobEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintStatusMessagesTest {

    @Test
    fun `sent job tells the operator it reached the printer and how many are still queued`() {
        val msg = PrintStatusMessages.of(PrintJobEvent.Completed("j", null, 120, 1), "Pesagem 96644", queuedCount = 3)!!
        assertEquals(NotificationKind.SUCCESS, msg.kind)
        assertEquals("Enviado à impressora", msg.title)
        assertEquals("Pesagem 96644 · 3 na fila", msg.text)
    }

    @Test
    fun `final failure is explicit with reason and attempts`() {
        val msg = PrintStatusMessages.of(PrintJobEvent.Failed("j", null, "Conexão recusada", 5), "Pesagem 96644", 0)!!
        assertEquals(NotificationKind.FAILURE, msg.kind)
        assertTrue(msg.text.contains("Conexão recusada"))
        assertTrue(msg.text.contains("5 tentativas"))
    }

    @Test
    fun `retry shows attempt counter so the operator knows it is not stuck`() {
        val msg = PrintStatusMessages.of(PrintJobEvent.Retrying("j", null, 2, 5, "Tempo de conexão esgotado", 4000), "Etiqueta", 0)!!
        assertEquals("Tentando novamente (2/5)", msg.title)
    }

    @Test
    fun `page of a document shows progress and does not spam a success per page`() {
        assertNull(PrintStatusMessages.of(PrintJobEvent.Completed("j", "g", 10, 1), "Relatório", 0))
        assertEquals(
            "Página 2 de 3 enviada",
            PrintStatusMessages.of(PrintJobEvent.PagePrinted("j", "g", 2, 3), "Relatório", 0)!!.text,
        )
    }

    @Test
    fun `document partially printed is reported as a failure not as success`() {
        val msg = PrintStatusMessages.of(PrintJobEvent.DocumentFinished("j", "g", 2, 3), "Relatório", 0)!!
        assertEquals(NotificationKind.FAILURE, msg.kind)
        assertTrue(msg.text.startsWith("2 de 3 páginas"))
    }

    @Test
    fun `page suffix is stripped from the document name`() {
        assertEquals("Relatório", PrintStatusMessages.baseName("Relatório (2/3)"))
        assertEquals("Etiqueta (lote 5)", PrintStatusMessages.baseName("Etiqueta (lote 5)"))
    }
}
