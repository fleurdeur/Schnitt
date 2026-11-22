package app.absentmotion.ai

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Creates a private, self-signed TLS identity for the local MCP server.  Modern
 * agents reject plain HTTP, so the server speaks HTTPS on the LAN.  The key is
 * generated on the device, kept in app-private storage, and never leaves it;
 * clients can fetch the public certificate from /certificate and trust it.
 */
internal object LocalTls {
    private const val ALIAS = "schnitt-mcp"
    private const val PASSWORD = "schnitt-local-mcp"
    private const val PREFS = "mcp_tls"
    private const val IPS = "ips"

    fun serverSocket(context: Context, port: Int): SSLServerSocket? = runCatching {
        val identity = identity(context)
        val kmf = KeyManagerFactory.getInstance("PKIX").apply { init(identity.store, identity.password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    }.getOrNull()

    fun certificatePem(context: Context): String? = runCatching {
        val cert = identity(context).certificate
        val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        "-----BEGIN CERTIFICATE-----\n" +
            encoded.chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
    }.getOrNull()

    private data class Identity(val store: KeyStore, val certificate: X509Certificate, val password: CharArray)

    @Synchronized
    private fun identity(context: Context): Identity {
        val file = File(context.filesDir, "mcp-tls.p12")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ips = lanIps()
        val saved = prefs.getString(IPS, null)
        val fresh = file.isFile && saved == ips.joinToString(",") && load(file)?.let { it.certificate.validFor(Long.MAX_VALUE / 2) } == true
        if (fresh) load(file)?.let { return it }
        return create(file, ips).also { prefs.edit().putString(IPS, ips.joinToString(",")).apply() }
    }

    private fun load(file: File): Identity? = runCatching {
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        val cert = store.getCertificate(ALIAS) as X509Certificate
        Identity(store, cert, PASSWORD.toCharArray())
    }.getOrNull()

    private fun X509Certificate.validFor(minRemainingMs: Long): Boolean = runCatching {
        checkValidity(Date(System.currentTimeMillis() + minRemainingMs))
    }.isSuccess

    private fun create(file: File, ips: List<String>): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val algorithm = seq(oid("1.2.840.113549.1.1.11"), nul())
        val name = seq(set(seq(oid("2.5.4.3"), utf8("Schnitt Local"))))
        val now = System.currentTimeMillis()
        val notBefore = utc(Date(now - 24L * 60 * 60 * 1000))
        val notAfter = utc(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
        val serial = BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) })

        val sanItems = ArrayList<ByteArray>()
        sanItems += tlv(0x82, "schnitt.local".toByteArray(Charsets.US_ASCII))
        sanItems += tlv(0x82, "localhost".toByteArray(Charsets.US_ASCII))
        for (ip in ips.distinct()) {
            val raw = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: continue
            sanItems += tlv(0x87, raw)
        }
        val extensions = seq(
            extension("2.5.29.19", true, seq()),
            extension("2.5.29.15", true, bitString(byteArrayOf(0xA0.toByte()), 5)),
            extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),
            extension("2.5.29.17", false, seq(*sanItems.toTypedArray())),
        )
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))),
            integer(serial),
            algorithm,
            name,
            seq(notBefore, notAfter),
            name,
            pair.public.encoded,
            tlv(0xA3, extensions),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algorithm, bitString(signature))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, pair.private, PASSWORD.toCharArray(), arrayOf(certificate))
        }
        file.outputStream().buffered().use { store.store(it, PASSWORD.toCharArray()) }
        return Identity(store, certificate, PASSWORD.toCharArray())
    }

    private fun lanIps(): List<String> = runCatching {
        (java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList()) + "127.0.0.1"

    private fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(id), boolean(true), octet(value)) else seq(oid(id), octet(value))

    private fun oid(value: String): ByteArray {
        val parts = value.split('.').map { it.toInt() }
        val out = ArrayList<Byte>()
        out += (parts[0] * 40 + parts[1]).toByte()
        for (part in parts.drop(2)) {
            if (part == 0) { out += 0.toByte(); continue }
            val chunk = ArrayList<Byte>()
            var n = part
            while (n > 0) { chunk.add(0, (n and 0x7F).toByte()); n = n shr 7 }
            for (i in chunk.indices) out += if (i < chunk.lastIndex) (chunk[i].toInt() or 0x80).toByte() else chunk[i]
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())
    private fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    private fun nul(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun utf8(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))
    private fun octet(value: ByteArray): ByteArray = tlv(0x04, value)
    private fun bitString(value: ByteArray, unused: Int = 0): ByteArray = tlv(0x03, byteArrayOf(unused.toByte()) + value)
    private fun seq(vararg items: ByteArray): ByteArray = tlv(0x30, items.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg items: ByteArray): ByteArray = tlv(0x31, items.fold(ByteArray(0)) { a, b -> a + b })
    private fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray {
        if (size < 128) return byteArrayOf(size.toByte())
        val bytes = ArrayList<Byte>()
        var n = size
        while (n > 0) { bytes.add(0, (n and 0xFF).toByte()); n = n shr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun utc(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }
}
