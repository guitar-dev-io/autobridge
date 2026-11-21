package dev.autobridge.obd

import android.content.Context
import dev.autobridge.obd.coding.CodingChange
import java.io.File

/** One value saved immediately before it was overwritten; Restore writes [before] back. */
class CodingBackup(val file: File, val module: String, val did: Int, val before: ByteArray, val after: ByteArray, val vin: String, val timeMs: Long)

/** Kept in the app's private storage, written before every coding write. */
object CodingBackupStore {
    private fun dir(context: Context) = File(context.filesDir, "coding_backups").apply { mkdirs() }

    fun save(context: Context, module: String, did: Int, before: ByteArray, after: ByteArray, vin: String?): CodingBackup {
        val now = System.currentTimeMillis()
        val file = File(dir(context), "${now}_${module}_%04X.txt".format(did))
        file.writeText("module=$module\ndid=%04X\nbefore=${CodingChange.hex(before)}\nafter=${CodingChange.hex(after)}\nvin=${vin ?: ""}\ntime=$now\n".format(did))
        return CodingBackup(file, module, did, before, after, vin ?: "", now)
    }

    fun list(context: Context): List<CodingBackup> =
        (dir(context).listFiles() ?: emptyArray()).sortedByDescending { it.name }.mapNotNull { f ->
            runCatching {
                val m = f.readLines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
                CodingBackup(f, m.getValue("module"), m.getValue("did").toInt(16), unhex(m.getValue("before")), unhex(m.getValue("after")), m["vin"].orEmpty(), m["time"]?.toLongOrNull() ?: 0L)
            }.getOrNull()
        }

    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
