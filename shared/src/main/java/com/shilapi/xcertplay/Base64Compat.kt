package com.shilapi.xcertplay

/**
 * java.util.Base64 is API 26+; this wraps kotlin.io.encoding with the same semantics so the
 * same code paths work on API 23 devices and in plain JVM unit tests.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
object Base64Compat {

    fun encode(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(bytes)

    /** Strict decode of the basic alphabet, matching java.util.Base64.getDecoder(). */
    fun decode(encoded: String): ByteArray = kotlin.io.encoding.Base64.decode(encoded)

    /** MIME-style decode that skips whitespace, matching java.util.Base64.getMimeDecoder(). */
    fun decodeMime(bytes: ByteArray): ByteArray =
        kotlin.io.encoding.Base64.decode(String(bytes, Charsets.US_ASCII).filterNot(Char::isWhitespace))

    /** PEM-style 64-column lines separated by '\n', matching java.util.Base64.getMimeEncoder(64, "\n"). */
    fun encodeMime(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.encode(bytes).chunked(MIME_LINE_LENGTH).joinToString("\n")

    private const val MIME_LINE_LENGTH = 64
}
