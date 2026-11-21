package dev.autobridge.aap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * The certificate chain and private key this phone presents to the head unit.
 *
 * In the Android Auto handshake the head unit is the TLS client and the phone the server, so this
 * is the server identity. A factory head unit only accepts a chain that ends at the Android Auto
 * certificate authority; AutoBridge ships no such key and does not obtain one. It loads whatever
 * the user supplies, and a head unit that checks the chain will refuse a self-made one - which
 * shows up in the connection log as a handshake alert from the car.
 */
class AapCredentials(val chain: List<X509Certificate>, val key: PrivateKey) {
    val subject: String get() = chain.firstOrNull()?.subjectX500Principal?.name.orEmpty()
    val notAfter: java.util.Date? get() = chain.firstOrNull()?.notAfter

    companion object {
        /**
         * Reads PEM text. Certificates and the key may be in one file or two (pass both texts
         * joined). Keys: PKCS#8 (`PRIVATE KEY`, RSA or EC) and PKCS#1 (`RSA PRIVATE KEY`).
         */
        fun fromPem(text: String): AapCredentials {
            val blocks = pemBlocks(text)
            val factory = CertificateFactory.getInstance("X.509")
            val chain = blocks.filter { it.first == "CERTIFICATE" }.map {
                factory.generateCertificate(ByteArrayInputStream(it.second)) as X509Certificate
            }
            if (chain.isEmpty()) throw IllegalArgumentException("no CERTIFICATE block found")
            val keyBlock = blocks.firstOrNull { it.first.endsWith("PRIVATE KEY") }
                ?: throw IllegalArgumentException("no PRIVATE KEY block found")
            val key = when (keyBlock.first) {
                "PRIVATE KEY" -> pkcs8(keyBlock.second, chain.first().publicKey.algorithm)
                "RSA PRIVATE KEY" -> pkcs8(rsaPkcs1ToPkcs8(keyBlock.second), "RSA")
                "ENCRYPTED PRIVATE KEY" -> throw IllegalArgumentException("the private key is password-protected; export it without a password")
                else -> throw IllegalArgumentException("unsupported key format: ${keyBlock.first}")
            }
            return AapCredentials(chain, key)
        }

        private fun pkcs8(der: ByteArray, algorithm: String): PrivateKey {
            val spec = PKCS8EncodedKeySpec(der)
            val tried = linkedSetOf(algorithm, "RSA", "EC")
            var last: Exception? = null
            for (alg in tried) {
                try {
                    return KeyFactory.getInstance(alg).generatePrivate(spec)
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IllegalArgumentException("private key could not be read: ${last?.message}")
        }

        internal fun pemBlocks(text: String): List<Pair<String, ByteArray>> {
            val regex = Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----")
            return regex.findAll(text).map { m ->
                val body = m.groupValues[2].lines().filterNot { it.contains(':') }.joinToString("") { it.trim() }
                m.groupValues[1] to Base64.getDecoder().decode(body)
            }.toList()
        }

        /** Wraps a PKCS#1 RSAPrivateKey in the PKCS#8 PrivateKeyInfo the JCA reads. */
        internal fun rsaPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
            val algorithmId = byteArrayOf(
                0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
            )
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val octet = derHeader(0x04, pkcs1.size) + pkcs1
            val content = version + algorithmId + octet
            return derHeader(0x30, content.size) + content
        }

        private fun derHeader(tag: Int, length: Int): ByteArray = when {
            length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
            length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length ushr 8).toByte(), length.toByte())
            else -> byteArrayOf(
                tag.toByte(), 0x83.toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()
            )
        }
    }
}

/**
 * TLS server side of the link, driven over memory buffers: handshake records travel inside
 * SSL_HANDSHAKE control messages, and afterwards each encrypted frame's payload is a run of TLS
 * records that decrypt on their own.
 */
class AapTls(credentials: AapCredentials) {
    private val engine: SSLEngine
    private var inNet: ByteBuffer
    private var appIn: ByteBuffer
    private val encryptLock = Any()
    private val decryptLock = Any()

    @Volatile
    var handshakeDone = false
        private set

    init {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("aap", credentials.key, PASSWORD, credentials.chain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        engine = context.createSSLEngine()
        engine.useClientMode = false
        // Head units speak TLS 1.2; 1.3 would also add post-handshake records they do not expect.
        if ("TLSv1.2" in engine.supportedProtocols) engine.enabledProtocols = arrayOf("TLSv1.2")
        engine.needClientAuth = false
        engine.wantClientAuth = false
        val session = engine.session
        inNet = ByteBuffer.allocate(session.packetBufferSize * 2)
        appIn = ByteBuffer.allocate(session.applicationBufferSize * 2)
        engine.beginHandshake()
    }

    val protocol: String get() = engine.session.protocol
    val cipherSuite: String get() = engine.session.cipherSuite

    /**
     * Feeds the head unit's handshake bytes and returns what to send back (possibly empty).
     * [handshakeDone] turns true once the server side has finished.
     */
    fun handshake(input: ByteArray): ByteArray = synchronized(decryptLock) {
        synchronized(encryptLock) {
            append(input)
            val out = ByteArrayOutputStream()
            loop@ while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                        inNet.flip()
                        val result = try {
                            engine.unwrap(inNet, appIn)
                        } finally {
                            inNet.compact()
                        }
                        when (result.status) {
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = grow(appIn, engine.session.applicationBufferSize)
                            SSLEngineResult.Status.CLOSED -> throw SSLException("head unit closed TLS during the handshake")
                            else -> Unit
                        }
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) handshakeDone = true
                    }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        var outNet = ByteBuffer.allocate(engine.session.packetBufferSize)
                        while (true) {
                            val result = engine.wrap(EMPTY, outNet)
                            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                                outNet = grow(outNet, engine.session.packetBufferSize); continue
                            }
                            if (result.status == SSLEngineResult.Status.CLOSED) {
                                throw SSLException("TLS closed during the handshake")
                            }
                            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) handshakeDone = true
                            break
                        }
                        outNet.flip()
                        out.write(outNet.array(), 0, outNet.limit())
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        while (true) engine.delegatedTask?.run() ?: break
                    }
                    else -> {
                        handshakeDone = true
                        break@loop
                    }
                }
            }
            out.toByteArray()
        }
    }

    fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size - offset): ByteArray = synchronized(encryptLock) {
        val src = ByteBuffer.wrap(plain, offset, length)
        val out = ByteArrayOutputStream(length + 64)
        var outNet = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (src.hasRemaining()) {
            outNet.clear()
            val result = engine.wrap(src, outNet)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { outNet = grow(outNet, engine.session.packetBufferSize); continue }
                SSLEngineResult.Status.CLOSED -> throw SSLException("TLS closed")
                else -> Unit
            }
            outNet.flip()
            out.write(outNet.array(), 0, outNet.limit())
        }
        out.toByteArray()
    }

    fun decrypt(cipher: ByteArray): ByteArray = synchronized(decryptLock) {
        append(cipher)
        val out = ByteArrayOutputStream(cipher.size)
        while (true) {
            inNet.flip()
            if (!inNet.hasRemaining()) { inNet.compact(); break }
            appIn.clear()
            val result = try {
                engine.unwrap(inNet, appIn)
            } finally {
                inNet.compact()
            }
            when (result.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { appIn = grow(appIn, engine.session.applicationBufferSize); continue }
                SSLEngineResult.Status.CLOSED -> throw SSLException("head unit closed TLS")
                else -> Unit
            }
            appIn.flip()
            out.write(appIn.array(), 0, appIn.limit())
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
        }
        out.toByteArray()
    }

    private fun append(data: ByteArray) {
        if (inNet.remaining() < data.size) {
            val bigger = ByteBuffer.allocate(inNet.position() + data.size + engine.session.packetBufferSize)
            inNet.flip()
            bigger.put(inNet)
            inNet = bigger
        }
        inNet.put(data)
    }

    private fun grow(buffer: ByteBuffer, atLeast: Int): ByteBuffer {
        val bigger = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, atLeast))
        buffer.flip()
        bigger.put(buffer)
        return bigger
    }

    private companion object {
        val PASSWORD = "aap".toCharArray()
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
