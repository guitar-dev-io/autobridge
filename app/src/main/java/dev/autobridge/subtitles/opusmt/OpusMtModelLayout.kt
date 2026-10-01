package dev.autobridge.subtitles.opusmt

/**
 * What a complete installed model looks like on disk, as rules rather than as files.
 *
 * Split out from [OpusMtModelStore] because this is the part with the decisions in it - whether a
 * directory is usable, and which directories a cleanup is allowed to remove - and those are worth
 * testing without a filesystem. A half-downloaded model that loaded anyway would fail later,
 * inside an ONNX Runtime session, as something that looks like a corrupt graph rather than as a
 * missing file.
 */
object OpusMtModelLayout {
    /** The directory everything is stored under, relative to the app's own files directory. */
    const val ROOT_DIRECTORY = "opus-mt"

    /**
     * The suffix a download in progress carries.
     *
     * Files are written into `<id>.partial` and the directory is renamed into place only once
     * every file has arrived, so a download killed halfway - by the process dying, or by the car
     * disconnecting - leaves something a later run can recognise and remove rather than a
     * directory that passes [isComplete] with a truncated graph in it.
     */
    const val PARTIAL_SUFFIX = ".partial"

    /** True when [present] holds every file a model needs. */
    fun isComplete(present: Collection<String>): Boolean = missing(present).isEmpty()

    /** The files a model directory is still short of. */
    fun missing(present: Collection<String>): List<OpusMtFile> {
        val names = present.toSet()
        return OpusMtFile.all.filterNot { names.contains(it.fileName) }
    }

    /** True when a directory name is a download that never finished. */
    fun isPartial(directoryName: String): Boolean = directoryName.endsWith(PARTIAL_SUFFIX)

    /**
     * The installed model ids a cleanup should delete, given everything present and the ids worth
     * keeping.
     *
     * [keep] is a set rather than a single id because the pair in the settings is not the only one
     * worth keeping: someone who watches in two languages would otherwise re-download a model
     * every time they switched. Partial directories are always deleted - they are not a model,
     * only the remains of one.
     */
    fun cleanupPlan(installed: Collection<String>, keep: Set<String>): List<String> =
        installed.filter { isPartial(it) || !keep.contains(it) }.sorted()

    /** A size in bytes as the settings screen says it; megabytes, because every model is one. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes < 1024L * 1024L -> "under 1 MB"
        else -> "${(bytes + 512L * 1024L) / (1024L * 1024L)} MB"
    }
}
