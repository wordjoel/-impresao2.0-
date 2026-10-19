package br.com.atos.printbridge.integration

import br.com.atos.printbridge.api.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedContentClassifierTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val pdf = "%PDF-1.7\n".toByteArray()

    @Test
    fun `foto da galeria vira documento IMAGE`() {
        assertEquals(SharedContent.Document(DocumentType.IMAGE), SharedContentClassifier.classify("image/jpeg", jpeg))
        assertEquals(SharedContent.Document(DocumentType.IMAGE), SharedContentClassifier.classify("image/heic", byteArrayOf(0, 0, 0, 0x18)))
    }

    @Test
    fun `pdf vira documento PDF`() {
        assertEquals(SharedContent.Document(DocumentType.PDF), SharedContentClassifier.classify("application/pdf", pdf))
    }

    @Test
    fun `mime generico usa a assinatura do arquivo`() {
        assertEquals(SharedContent.Document(DocumentType.IMAGE), SharedContentClassifier.classify("application/octet-stream", jpeg))
        assertEquals(SharedContent.Document(DocumentType.PDF), SharedContentClassifier.classify(null, pdf))
    }

    @Test
    fun `zpl e texto continuam indo como vieram`() {
        assertEquals(SharedContent.Zpl, SharedContentClassifier.classify("text/plain", "^XA^FDoi^FS^XZ".toByteArray()))
        assertEquals(SharedContent.Zpl, SharedContentClassifier.classify(null, "^XA^XZ".toByteArray()))
        assertEquals(SharedContent.Zpl, SharedContentClassifier.classify("application/octet-stream", "^XA^XZ".toByteArray()))
    }

    @Test
    fun `video e audio sao recusados e binario sem tipo e desconhecido`() {
        assertEquals(SharedContent.Unsupported, SharedContentClassifier.classify("video/mp4", byteArrayOf(0, 0, 0, 0x20)))
        assertEquals(SharedContent.Unsupported, SharedContentClassifier.classify("audio/mpeg", byteArrayOf(0x49, 0x44, 0x33)))
        assertEquals(SharedContent.Unknown, SharedContentClassifier.classify(null, byteArrayOf(0, 1, 2, 3)))
    }
}
