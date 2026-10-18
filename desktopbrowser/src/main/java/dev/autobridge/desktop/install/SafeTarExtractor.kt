package dev.autobridge.desktop.install

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts an (already decompressed) tar stream into an empty directory without ever writing
 * outside it.
 *
 * A rootfs tarball is full of symlinks, many of them absolute (`/usr/lib/...`). They are created as
 * links, never followed: every parent directory of an entry is checked with `lstat` semantics
 * ([LinkOption.NOFOLLOW_LINKS]), so an archive that plants `a -> /data/...` and then writes `a/x`
 * is rejected instead of escaping. Names with `..` components are rejected, absolute names are
 * made relative, set-id bits are dropped and only the owner's write bit is forced on so the app can
 * later update or delete what it extracted. Device nodes and FIFOs are skipped: the runtime binds
 * the host's `/dev` instead.
 *
 * Supports ustar, GNU long names/links (`L`/`K`) and pax extended headers (`x`), which is what
 * Debian's rootfs layers use.
 */
class SafeTarExtractor(
    private val isCancelled: () -> Boolean = { false },
    private val onBytes: (Long) -> Unit = {},
) {
    class UnsafeEntryException(message: String) : IOException(message)

    data class Stats(val files: Int, val directories: Int, val symlinks: Int, val hardlinks: Int, val skipped: Int, val bytes: Long)

    fun extract(input: InputStream, destination: File): Stats {
        val root = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val header = ByteArray(BLOCK)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        var skipped = 0
        var bytes = 0L
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var zeroBlocks = 0
        val deferredDirModes = ArrayList<Pair<Path, Int>>()

        while (true) {
            if (isCancelled()) throw InterruptedIOException("extraction cancelled")
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) {
                if (++zeroBlocks == 2) break
                continue
            }
            zeroBlocks = 0
            verifyChecksum(header)
            val type = header[156].toInt().toChar()
            val size = paxSize ?: parseNumber(header, 124, 12)
            val rawName = longName ?: paxPath ?: ustarName(header)
            val rawLink = longLink ?: paxLink ?: string(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt()

            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> {
                    val pax = parsePax(readString(input, size))
                    paxPath = pax["path"]
                    paxLink = pax["linkpath"]
                    paxSize = pax["size"]?.toLongOrNull()
                    continue
                }
                'g' -> { skip(input, padded(size)); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = null

            val relative = safeRelative(rawName) ?: run { skip(input, padded(size)); null }
            if (relative == null) continue
            val target = root.resolve(relative).normalize()
            if (!target.startsWith(root)) throw UnsafeEntryException("entry escapes root: $rawName")
            ensureRealParents(root, target)

            when (type) {
                '5' -> {
                    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        removeExisting(target)
                        Files.createDirectory(target)
                    }
                    deferredDirModes += target to mode
                    dirs++
                }
                '2' -> {
                    removeExisting(target)
                    Files.createSymbolicLink(target, java.nio.file.Paths.get(rawLink))
                    symlinks++
                }
                '1' -> {
                    val linkRelative = safeRelative(rawLink) ?: throw UnsafeEntryException("bad hardlink target: $rawLink")
                    val source = root.resolve(linkRelative).normalize()
                    if (!source.startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw UnsafeEntryException("hardlink target missing or unsafe: $rawLink")
                    }
                    removeExisting(target)
                    try {
                        Files.createLink(target, source)
                    } catch (_: Exception) {
                        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    hardlinks++
                }
                '0', '\u0000', '7' -> {
                    removeExisting(target)
                    Files.newOutputStream(target).use { out ->
                        copy(input, out, size)
                    }
                    skip(input, padded(size) - size)
                    applyMode(target, mode or 0x180 /* rw for owner */)
                    files++
                    bytes += size
                    onBytes(bytes)
                }
                else -> {
                    // '3' char, '4' block, '6' fifo and unknown types: nothing an app may create.
                    skip(input, padded(size))
                    skipped++
                }
            }
        }
        // Directory modes last, so a read-only directory never blocks writing its own children.
        for ((dir, mode) in deferredDirModes.asReversed()) applyMode(dir, mode or 0x1c0 /* rwx for owner */)
        return Stats(files, dirs, symlinks, hardlinks, skipped, bytes)
    }

    class InterruptedIOException(message: String) : IOException(message)

    private fun ensureRealParents(root: Path, target: Path) {
        var current = root
        val parent = target.parent ?: return
        for (part in root.relativize(parent)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw UnsafeEntryException("path goes through a symlink: ${root.relativize(target)}")
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current)
            } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw UnsafeEntryException("parent is not a directory: ${root.relativize(current)}")
            }
        }
    }

    private fun removeExisting(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.deleteIfExists(path)
    }

    private fun applyMode(path: Path, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val bits = arrayOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        for (i in bits.indices) if (mode and (1 shl (8 - i)) != 0) perms += bits[i]
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            if (isCancelled()) throw InterruptedIOException("extraction cancelled")
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw EOFException("truncated archive")
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun readString(input: InputStream, size: Long): String {
        if (size > MAX_META) throw UnsafeEntryException("metadata entry too large: $size")
        val bytes = ByteArray(size.toInt())
        if (!readFully(input, bytes)) throw EOFException("truncated archive")
        skip(input, padded(size) - size)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(8192)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw EOFException("truncated archive")
            left -= read
        }
    }

    companion object {
        private const val BLOCK = 512
        private const val MAX_META = 1L shl 20

        fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

        /** Relative, normalized form of an archive name, or null for the archive root itself. */
        fun safeRelative(name: String): String? {
            val parts = ArrayList<String>()
            for (part in name.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> throw UnsafeEntryException("parent reference in entry: $name")
                    else -> parts += part
                }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
        }

        fun parsePax(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (line in text.split('\n')) {
                val space = line.indexOf(' ')
                if (space < 0) continue
                val record = line.substring(space + 1)
                val eq = record.indexOf('=')
                if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            }
            return out
        }

        fun parseNumber(header: ByteArray, offset: Int, length: Int): Long {
            if (header[offset].toInt() and 0x80 != 0) {
                // GNU base-256 for sizes over 8 GiB.
                var value = (header[offset].toLong() and 0x7f)
                for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 0xff)
                return value
            }
            val text = string(header, offset, length).trim()
            return if (text.isEmpty()) 0 else text.toLong(8)
        }

        private fun ustarName(header: ByteArray): String {
            val name = string(header, 0, 100)
            val magic = string(header, 257, 6)
            val prefix = if (magic.startsWith("ustar")) string(header, 345, 155) else ""
            return if (prefix.isEmpty()) name else "$prefix/$name"
        }

        private fun string(header: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && header[end] != 0.toByte()) end++
            return String(header, offset, end - offset, Charsets.UTF_8)
        }

        private fun verifyChecksum(header: ByteArray) {
            val stored = parseNumber(header, 148, 8)
            var sum = 0L
            for (i in 0 until BLOCK) sum += if (i in 148 until 156) 32 else (header[i].toLong() and 0xff)
            if (stored != sum) throw IOException("tar header checksum mismatch")
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) {
                    if (read == 0) return false
                    throw EOFException("truncated archive")
                }
                read += n
            }
            return true
        }
    }
}

