package dev.autobridge.desktop.install

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Delete and measure trees that contain symlinks pointing anywhere.
 *
 * A rootfs has absolute symlinks (`/proc/self/fd`, `/usr/lib/...`). On the host those resolve to
 * the phone's own paths, so anything that follows links — `File.deleteRecursively`, which uses
 * `isDirectory` — could walk out of the rootfs and delete or count the wrong files. These walks
 * use NIO's file-tree walker, which never follows links: a link is deleted or counted as a link.
 */
object SafeFiles {
    fun deleteTree(file: File) {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { Files.delete(root); return }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A directory left without owner rwx by a package would make its children undeletable.
                runCatching { dir.toFile().setWritable(true, true); dir.toFile().setExecutable(true, true); dir.toFile().setReadable(true, true) }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Sum of regular-file sizes under [file]; links count as nothing. */
    fun sizeOf(file: File): Long {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }
}
