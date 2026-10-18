package dev.autobridge.desktop.install

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * Reads the primary-key fingerprints out of an OpenPGP public key file (binary or ASCII-armored).
 *
 * A browser vendor's repository is trusted through its signing key, so the key file fetched over
 * HTTPS is checked against fingerprints pinned in the adapter before apt ever sees it. Only the
 * fingerprint calculation is done here (RFC 4880 v4, RFC 9580 v6, draft v5); signatures are left
 * to apt, which verifies every Release file against the keyring this check let through.
 */
object OpenPgpKeys {
    class KeyFormatException(message: String) : IOException(message)

    fun primaryFingerprints(data: ByteArray): List<String> {
        val bytes = if (isArmored(data)) dearmor(String(data, Charsets.US_ASCII)) else data
        val out = ArrayList<String>()
        var pos = 0
        while (pos < bytes.size) {
            val tagByte = bytes[pos].toInt() and 0xff
            if (tagByte and 0x80 == 0) throw KeyFormatException("not an OpenPGP packet at $pos")
            val tag: Int
            val length: Int
            var header: Int
            if (tagByte and 0x40 != 0) {
                tag = tagByte and 0x3f
                val first = bytes.u8(pos + 1)
                when {
                    first < 192 -> { length = first; header = 2 }
                    first < 224 -> { length = ((first - 192) shl 8) + bytes.u8(pos + 2) + 192; header = 3 }
                    first == 255 -> { length = bytes.u32(pos + 2); header = 6 }
                    else -> throw KeyFormatException("partial-length packet in a key file")
                }
            } else {
                tag = (tagByte shr 2) and 0x0f
                when (tagByte and 0x03) {
                    0 -> { length = bytes.u8(pos + 1); header = 2 }
                    1 -> { length = (bytes.u8(pos + 1) shl 8) or bytes.u8(pos + 2); header = 3 }
                    2 -> { length = bytes.u32(pos + 1); header = 5 }
                    else -> { length = bytes.size - pos - 1; header = 1 }
                }
            }
            val start = pos + header
            val end = start + length
            if (length < 0 || end > bytes.size) throw KeyFormatException("truncated packet")
            if (tag == PUBLIC_KEY) out += fingerprint(bytes.copyOfRange(start, end))
            pos = end
        }
        return out
    }

    /** Uppercase hex without spaces, the form vendors publish (spaces removed). */
    fun normalize(fingerprint: String): String = fingerprint.replace(" ", "").uppercase()

    private fun fingerprint(body: ByteArray): String {
        if (body.isEmpty()) throw KeyFormatException("empty key packet")
        val digest = when (val version = body[0].toInt()) {
            4 -> MessageDigest.getInstance("SHA-1").apply {
                update(0x99.toByte())
                update((body.size shr 8).toByte()); update(body.size.toByte())
                update(body)
            }
            5, 6 -> MessageDigest.getInstance("SHA-256").apply {
                update((if (version == 5) 0x9A else 0x9B).toByte())
                update((body.size ushr 24).toByte()); update((body.size ushr 16).toByte())
                update((body.size ushr 8).toByte()); update(body.size.toByte())
                update(body)
            }
            else -> throw KeyFormatException("unsupported key version $version")
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun isArmored(data: ByteArray): Boolean =
        String(data, 0, minOf(data.size, 64), Charsets.US_ASCII).trimStart().startsWith("-----BEGIN PGP")

    private fun dearmor(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var inBlock = false
        var inBody = false
        val body = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK") -> { inBlock = true; inBody = false; body.clear() }
                line.startsWith("-----END PGP PUBLIC KEY BLOCK") -> {
                    out.write(Base64.getMimeDecoder().decode(body.toString()))
                    inBlock = false
                }
                !inBlock -> Unit
                !inBody -> if (line.isEmpty()) inBody = true else if (!line.contains(':')) { inBody = true; body.append(line) }
                line.startsWith("=") && line.length == 5 -> Unit // CRC24 trailer
                else -> body.append(line)
            }
        }
        if (out.size() == 0) throw KeyFormatException("no armored key block")
        return out.toByteArray()
    }

    private fun ByteArray.u8(i: Int): Int = if (i < size) this[i].toInt() and 0xff else throw KeyFormatException("truncated header")
    private fun ByteArray.u32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

    private const val PUBLIC_KEY = 6
}
