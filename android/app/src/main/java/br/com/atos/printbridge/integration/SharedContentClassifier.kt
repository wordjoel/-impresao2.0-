package br.com.atos.printbridge.integration

import br.com.atos.printbridge.api.DocumentType

/** O que fazer com um arquivo recebido pelo "Compartilhar" do Android. */
sealed interface SharedContent {
    /** Foto ou PDF: rasteriza para o papel/dpi da impressora, pelo mesmo caminho da API e do "Imprimir". */
    data class Document(val type: DocumentType) : SharedContent

    /** Texto/ZPL: vai para a fila como veio. */
    data object Zpl : SharedContent

    /** Vídeo/áudio: não há o que imprimir. */
    data object Unsupported : SharedContent

    data object Unknown : SharedContent
}

object SharedContentClassifier {
    /**
     * Decide pelo MIME e, quando ele falta ou é genérico, pela assinatura do arquivo — há galerias
     * que compartilham foto como `application/octet-stream`.
     */
    fun classify(mime: String?, head: ByteArray): SharedContent {
        val type = mime?.lowercase()?.substringBefore(';')?.trim()
        return when {
            type == "application/pdf" || head.startsWith(PDF_MAGIC) -> SharedContent.Document(DocumentType.PDF)
            type?.startsWith("image/") == true || looksLikeImage(head) -> SharedContent.Document(DocumentType.IMAGE)
            type != null && (type.startsWith("video/") || type.startsWith("audio/")) -> SharedContent.Unsupported
            startsWithZplHeader(head) -> SharedContent.Zpl
            type != null && (type.startsWith("text/") || type.contains("zpl") || type == "application/octet-stream") -> SharedContent.Zpl
            // MIME desconhecido: só aceita se o conteúdo tiver cara de texto puro (ZPL é ASCII).
            type == null && looksLikeText(head) -> SharedContent.Zpl
            else -> SharedContent.Unknown
        }
    }

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    private fun looksLikeImage(head: ByteArray): Boolean =
        head.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) || // JPEG
            head.startsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) || // PNG
            head.startsWith("GIF8".toByteArray(Charsets.US_ASCII)) ||
            head.startsWith("BM".toByteArray(Charsets.US_ASCII)) ||
            (head.startsWith("RIFF".toByteArray(Charsets.US_ASCII)) && head.copyOfRange(8, minOf(12, head.size)).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)))

    private fun startsWithZplHeader(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val head = String(bytes.take(16).toByteArray(), Charsets.US_ASCII).trimStart()
        return head.startsWith("^XA") || head.startsWith("^FX") || head.startsWith("^FO")
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return bytes.take(2048).all { byte ->
            byte.toInt() == 9 || byte.toInt() == 10 || byte.toInt() == 13 || byte.toInt() in 32..126
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
