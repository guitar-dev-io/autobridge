package dev.autobridge.expense

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Other car expenses, on this phone only, as a small JSON list like the fuel log. */
object ExpenseStore {
    private const val PREFS = "autobridge_expenses"
    private const val KEY_ITEMS = "items"

    fun all(context: Context): List<Expense> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Expense(item.optLong("id"), item.optLong("time"), item.optString("label"), item.optDouble("baht", 0.0))
                .takeIf { it.timeMs > 0 && it.baht > 0 }
        }.sortedByDescending { it.timeMs }
    }

    /** Adds an expense; false when the amount is not above zero. */
    fun add(context: Context, label: String, baht: Double, timeMs: Long = System.currentTimeMillis()): Boolean {
        if (baht <= 0 || baht.isNaN() || baht.isInfinite()) return false
        val id = maxOf(System.currentTimeMillis(), (all(context).maxOfOrNull { it.id } ?: 0L) + 1)
        write(context, all(context) + Expense(id, timeMs, label.trim(), baht))
        return true
    }

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /** The stored list as JSON, for a backup; "[]" when there is none. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY_ITEMS, null) ?: "[]"

    /** Replaces the list with a backup's. Returns false, changing nothing, if it is not a list. */
    fun restore(context: Context, raw: String): Boolean {
        if (runCatching { JSONArray(raw) }.isFailure) return false
        prefs(context).edit { putString(KEY_ITEMS, raw) }
        return true
    }

    private fun write(context: Context, items: List<Expense>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("time", it.timeMs).put("label", it.label).put("baht", it.baht)) }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
