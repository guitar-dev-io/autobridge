package dev.autobridge.obd.log

import dev.autobridge.obd.obd2.PidValue

/**
 * An in-memory recording of live readings, exported as CSV. One row per polling cycle, one column
 * per PID seen. Capped so a forgotten recording cannot grow without bound; the oldest rows go first.
 */
class ObdLog(private val maxRows: Int = 20_000) {
    private class Row(val timeMs: Long, val values: Map<Int, PidValue>)

    private val rows = ArrayDeque<Row>()
    private val columns = LinkedHashMap<Int, PidValue>() // first value seen per PID, for name and unit

    @get:Synchronized val size: Int get() = rows.size

    @Synchronized
    fun add(timeMs: Long, values: Map<Int, PidValue>) {
        if (values.isEmpty()) return
        values.forEach { (id, v) -> columns.putIfAbsent(id, v) }
        rows.addLast(Row(timeMs, values))
        while (rows.size > maxRows) rows.removeFirst()
    }

    @Synchronized
    fun clear() { rows.clear(); columns.clear() }

    /** `time_ms` then one column per PID, headed "Name (unit)". Missing readings are left empty. */
    @Synchronized
    fun csv(): String {
        val ids = columns.keys.sorted()
        val sb = StringBuilder("time_ms")
        for (id in ids) {
            val pid = columns.getValue(id).pid
            sb.append(',').append(quote(if (pid.unit.isEmpty()) pid.name else "${pid.name} (${pid.unit})"))
        }
        sb.append('\n')
        for (row in rows) {
            sb.append(row.timeMs)
            for (id in ids) {
                sb.append(',')
                row.values[id]?.let { sb.append(plain(it.value)) }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun quote(s: String) = if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun plain(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(java.util.Locale.US, v)
}
