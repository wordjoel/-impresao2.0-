package br.com.atos.printbridge.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentSharesTest {

    @Before
    fun setUp() = RecentShares.clear()

    @Test
    fun `mesmo arquivo compartilhado de novo dentro da janela e ignorado`() {
        assertTrue(RecentShares.claim("content://foto/1", 1_000))
        assertFalse(RecentShares.claim("content://foto/1", 11_000))
    }

    @Test
    fun `depois da janela o mesmo arquivo pode ser reenviado`() {
        assertTrue(RecentShares.claim("content://foto/1", 1_000))
        assertTrue(RecentShares.claim("content://foto/1", 1_000 + RecentShares.WINDOW_MILLIS))
    }

    @Test
    fun `arquivos diferentes nao se bloqueiam`() {
        assertTrue(RecentShares.claim("content://foto/1", 1_000))
        assertTrue(RecentShares.claim("content://foto/2", 1_500))
    }
}
