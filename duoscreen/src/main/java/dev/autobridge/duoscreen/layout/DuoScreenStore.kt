package dev.autobridge.duoscreen.layout

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.json.JSONArray
import org.json.JSONObject

/** A saved layout, with the surface size it was arranged on so it can be re-fitted to another. */
data class DuoScreenSavedLayout(val bounds: Bounds, val panes: List<DuoScreenPane>)

/**
 * Pure encode/decode/re-fit for the saved layout, kept out of [DuoScreenStore] so it runs on the
 * JVM without a Context.
 */
object DuoScreenLayoutCodec {
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_PANES = "panes"
    private const val KEY_PACKAGE = "pkg"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_WIDTH_PX = "w"
    private const val KEY_HEIGHT_PX = "h"

    fun encode(bounds: Bounds, panes: List<DuoScreenPane>): String {
        val array = JSONArray()
        panes.forEach { pane ->
            array.put(
                JSONObject().apply {
                    put(KEY_PACKAGE, pane.packageName ?: JSONObject.NULL)
                    put(KEY_LEFT, pane.rect.left)
                    put(KEY_TOP, pane.rect.top)
                    put(KEY_WIDTH_PX, pane.rect.width)
                    put(KEY_HEIGHT_PX, pane.rect.height)
                }
            )
        }
        return JSONObject().apply {
            put(KEY_WIDTH, bounds.width)
            put(KEY_HEIGHT, bounds.height)
            put(KEY_PANES, array)
        }.toString()
    }

    /** Returns null for anything unparseable, so a corrupt entry falls back to the default layout. */
    fun decode(json: String?): DuoScreenSavedLayout? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val bounds = Bounds(root.getInt(KEY_WIDTH), root.getInt(KEY_HEIGHT))
            if (bounds.width <= 0 || bounds.height <= 0) return null
            val array = root.getJSONArray(KEY_PANES)
            val panes = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                DuoScreenPane(
                    id = index,
                    packageName = entry.optString(KEY_PACKAGE).takeIf {
                        it.isNotEmpty() && !entry.isNull(KEY_PACKAGE)
                    },
                    rect = Rect(
                        entry.getInt(KEY_LEFT),
                        entry.getInt(KEY_TOP),
                        entry.getInt(KEY_WIDTH_PX),
                        entry.getInt(KEY_HEIGHT_PX)
                    )
                )
            }
            if (panes.isEmpty()) null else DuoScreenSavedLayout(bounds, panes)
        }.getOrNull()
    }

    /**
     * Re-fits a layout arranged on [from] onto [to] by scaling each rect proportionally, then
     * clamping — a head unit with a different panel, or the same one in another orientation, would
     * otherwise restore panes that hang off the edge or fall under the minimum size.
     */
    fun refit(panes: List<DuoScreenPane>, from: Bounds, to: Bounds): List<DuoScreenPane> {
        if (from.width <= 0 || from.height <= 0) return panes
        if (from.width == to.width && from.height == to.height) {
            return panes.map { it.copy(rect = DuoScreenLayout.clamp(it.rect, to)) }
        }
        val scaleX = to.width.toFloat() / from.width
        val scaleY = to.height.toFloat() / from.height
        return panes.map { pane ->
            val scaled = Rect(
                left = (pane.rect.left * scaleX).toInt(),
                top = (pane.rect.top * scaleY).toInt(),
                width = (pane.rect.width * scaleX).toInt(),
                height = (pane.rect.height * scaleY).toInt()
            )
            pane.copy(rect = DuoScreenLayout.clamp(scaled, to))
        }
    }
}

/** Persists the arrangement and the app chosen for each pane across sessions. */
object DuoScreenStore {
    private const val PREFS_NAME = "autobridge_duo_screen"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_PANE_COUNT = "pane_count"
    private const val KEY_PRESET = "preset"

    /**
     * Whether the stored rects are the driver's own arrangement rather than a preset laid out for
     * them. Only an arrangement is restored as-is; a preset is re-derived from the real surface, so
     * picking one on the phone (where there is no surface, and the rects would be written against a
     * placeholder size) still lands exactly right on the head unit.
     */
    private const val KEY_ARRANGED = "arranged"
    private const val KEY_CONTENT_SCALE = "content_scale"
    private const val DEFAULT_PANE_COUNT = 2

    const val MIN_PANES = 2
    const val MAX_PANES = 3

    /**
     * How much of a pane the app inside it gets to use, as a percentage.
     *
     * A pane's display runs at the pane's own pixel size, so the only thing that decides how much
     * an app can fit in it is density: at the car panel's own dpi one pixel is one dp, and half an
     * 800x400 panel leaves the app a 400x400dp screen — a third of the height a phone app is drawn
     * for, which is why everything in it looks crammed. Dividing the density by this gives the app
     * more dp for the same pixels: 150% turns that pane into 600x600dp. Nothing is scaled or
     * stretched — the app simply lays itself out for a roomier screen and draws smaller.
     *
     * A short list rather than a free number: these are the steps worth having from a driver's
     * seat, and each one is still legible on a car panel.
     */
    val CONTENT_SCALES = listOf(100, 125, 150, 200)

    /**
     * Roomier than the panel says by default. 100% is the honest reading of the car's own density,
     * and on the 800x400 panels these head units have it is too tight to be useful.
     */
    private const val DEFAULT_CONTENT_SCALE = 150

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun paneCount(context: Context): Int =
        prefs(context).getInt(KEY_PANE_COUNT, DEFAULT_PANE_COUNT).coerceIn(MIN_PANES, MAX_PANES)

    fun setPaneCount(context: Context, count: Int) {
        prefs(context).edit {
            putInt(KEY_PANE_COUNT, count.coerceIn(MIN_PANES, MAX_PANES))
            // The stored arrangement was built for the old count, so it cannot describe the new one.
            putBoolean(KEY_ARRANGED, false)
        }
    }

    fun preset(context: Context): DuoScreenPreset =
        prefs(context).getString(KEY_PRESET, null)
            ?.let { name -> DuoScreenPreset.entries.firstOrNull { it.name == name } }
            ?: DuoScreenPreset.EVEN_COLUMNS

    /** Choosing a preset drops the hand-made arrangement; the chosen apps are kept. */
    fun setPreset(context: Context, preset: DuoScreenPreset) {
        prefs(context).edit {
            putString(KEY_PRESET, preset.name)
            putBoolean(KEY_ARRANGED, false)
        }
    }

    /** The panes [preset] gives on a surface of [bounds], carrying the apps already chosen. */
    fun presetPanes(context: Context, bounds: Bounds): List<DuoScreenPane> {
        val packages = packages(context)
        return preset(context).rects(packages.size, bounds).mapIndexed { index, rect ->
            DuoScreenPane(index, packages.getOrNull(index), rect)
        }
    }

    fun save(context: Context, bounds: Bounds, panes: List<DuoScreenPane>) {
        if (panes.isEmpty()) return
        prefs(context).edit {
            putString(KEY_LAYOUT, DuoScreenLayoutCodec.encode(bounds, panes))
            putBoolean(KEY_ARRANGED, true)
        }
    }

    /**
     * The driver's own arrangement re-fitted to [bounds], or null when they have none — which is
     * the signal to lay the panes out from [presetPanes] instead.
     */
    fun restore(context: Context, bounds: Bounds): List<DuoScreenPane>? {
        if (!prefs(context).getBoolean(KEY_ARRANGED, false)) return null
        val saved = DuoScreenLayoutCodec.decode(prefs(context).getString(KEY_LAYOUT, null)) ?: return null
        return DuoScreenLayoutCodec.refit(saved.panes, saved.bounds, bounds)
    }

    /** Stored packages only, for the settings screen, which has no surface and so no layout. */
    fun packages(context: Context): List<String?> {
        val saved = DuoScreenLayoutCodec.decode(prefs(context).getString(KEY_LAYOUT, null))
        val count = paneCount(context)
        return (0 until count).map { index -> saved?.panes?.getOrNull(index)?.packageName }
    }

    /** Changing a pane's app leaves the arrangement — and whether there is one — exactly as it was. */
    fun setPackage(context: Context, paneId: Int, packageName: String?) {
        val stored = DuoScreenLayoutCodec.decode(prefs(context).getString(KEY_LAYOUT, null))
        val count = paneCount(context)
        val bounds = stored?.bounds ?: Bounds(count * 100, 100)
        val panes = (0 until count).map { index ->
            val existing = stored?.panes?.getOrNull(index)
            val packageForPane = if (index == paneId) packageName else existing?.packageName
            DuoScreenPane(
                id = index,
                packageName = packageForPane,
                rect = existing?.rect ?: DuoScreenPaneLayouts.evenColumns(count, bounds)[index]
            )
        }
        prefs(context).edit { putString(KEY_LAYOUT, DuoScreenLayoutCodec.encode(bounds, panes)) }
    }

    fun contentScale(context: Context): Int =
        prefs(context).getInt(KEY_CONTENT_SCALE, DEFAULT_CONTENT_SCALE)
            .takeIf { it in CONTENT_SCALES } ?: DEFAULT_CONTENT_SCALE

    /** Density only: the panes keep their rects, so there is no arrangement to drop. */
    fun setContentScale(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_CONTENT_SCALE, percent) }
    }

    /** Drops the arrangement but keeps the chosen apps, which is what "reset layout" means. */
    fun resetLayout(context: Context) {
        prefs(context).edit { putBoolean(KEY_ARRANGED, false) }
    }
}
