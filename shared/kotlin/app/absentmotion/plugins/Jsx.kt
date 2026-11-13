package app.absentmotion.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Best-effort ExtendScript reader.  Plain .jsx files are used directly.  Many
 * .jsxbin files are a marker followed by a compressed script; we unpack the
 * common zlib, gzip and raw-deflate containers and keep any readable source.
 * Locked binaries that use Adobe's private cipher are reported, never faked.
 */
object Jsx {
    private const val LIMIT = 8 shl 20

    fun decode(file: File): String? = runCatching { decode(file.readBytes()) }.getOrNull()

    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (text != null && text.contains("@JSXBIN@")) {
            val body = text.substringAfter("@JSXBIN@").trim()
            val raw = runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return null
            return inflate(raw) ?: readable(raw)
        }
        if (text != null && printable(text) > 0.86) return text
        return null
    }

    fun isPlainSource(text: String): Boolean = listOf("function", "var ", "const ", "let ", "app.", "document.", "//", "/*").any { it in text }

    private fun inflate(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val candidates = buildList {
            add { GZIPInputStream(bytes.inputStream()) }
            add { InflaterInputStream(bytes.inputStream(), Inflater()) }
            add { InflaterInputStream(bytes.inputStream(), Inflater(true)) }
        }
        for (open in candidates) {
            runCatching {
                open().use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (total < LIMIT) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        out.write(buffer, 0, read)
                    }
                    val decoded = out.toByteArray().toString(Charsets.UTF_8)
                    if (decoded.isNotBlank() && printable(decoded) > 0.75) return decoded
                }
            }
        }
        return null
    }

    private fun readable(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val runs = Regex("[\\x20-\\x7E\\r\\n\\t]{12,}").findAll(text).map { it.value }.filter { isPlainSource(it) }.toList()
        return runs.joinToString("\n").takeIf { isPlainSource(it) }
    }

    private fun printable(text: String): Double {
        if (text.isEmpty()) return 0.0
        val good = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
        return good.toDouble() / text.length
    }
}
