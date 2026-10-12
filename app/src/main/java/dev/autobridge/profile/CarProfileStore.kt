package dev.autobridge.profile

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.core.model.Insets
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.logging.StructuredLog
import org.json.JSONArray

/**
 * The car profiles saved on this phone, and which one is in use.
 *
 * Each is kept as its shared-file JSON ([CarProfileCodec]), so what is stored is exactly what can
 * be exported again, and reading it back goes through the same checks as an import.
 */
object CarProfileStore {
    private const val TAG = "CarProfiles"
    private const val PREFS = "autobridge_car_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected"
    private const val MAX_PROFILES = 20

    fun list(context: Context): List<CarProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            (CarProfileCodec.decode(item.optString("profile"), id) as? CarProfileCodec.Result.Ok)?.profile
        }
    }

    /** Saves [profile], newest first; the oldest beyond [MAX_PROFILES] goes. */
    fun add(context: Context, profile: CarProfile) {
        save(context, (listOf(profile) + list(context).filterNot { it.id == profile.id }).take(MAX_PROFILES))
        StructuredLog.i(TAG, "saved ${profile.name} screen=${profile.screen?.label}")
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
        if (selectedId(context) == id) select(context, null)
    }

    fun newId(): String = "p" + System.currentTimeMillis().toString(36) + (0..999).random()

    fun selectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    /** Uses [id] for the car, or a built-in [SurfaceProfile] again when null. */
    fun select(context: Context, id: String?) {
        prefs(context).edit { if (id == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, id) }
        ActiveCarProfile.custom = id?.let { wanted -> list(context).firstOrNull { it.id == wanted } }
        StructuredLog.i(TAG, "in use: ${ActiveCarProfile.label}")
    }

    /** Re-applies the saved choice at start-up. */
    fun restore(context: Context) {
        val id = selectedId(context) ?: return
        ActiveCarProfile.custom = list(context).firstOrNull { it.id == id }
    }

    private fun save(context: Context, profiles: List<CarProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(org.json.JSONObject().apply {
                put("id", profile.id)
                put("profile", CarProfileCodec.encode(profile))
            })
        }
        prefs(context).edit { putString(KEY_PROFILES, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The values the mirror applies for the car: a saved profile when one is selected, otherwise the
 * built-in [SurfaceProfile] chosen in settings. Every consumer reads through here, so a selected
 * profile reaches the dpi fallback, the safe insets, the touch offsets and the frame rate at once.
 */
object ActiveCarProfile {
    @Volatile
    var custom: CarProfile? = null

    val fallbackDpi: Int get() = custom?.fallbackDpi ?: SurfaceProfile.active.fallbackDpi
    val safeInsets: Insets get() = custom?.safeInsets ?: SurfaceProfile.active.safeInsets
    val touchOffsetX: Float get() = custom?.touchOffsetX ?: SurfaceProfile.active.touchOffsetX
    val touchOffsetY: Float get() = custom?.touchOffsetY ?: SurfaceProfile.active.touchOffsetY
    val preferredFps: Int? get() = custom.let { if (it != null) it.preferredFps else SurfaceProfile.active.preferredFps }
    val label: String get() = custom?.name ?: SurfaceProfile.active.displayLabel

    /** False for any saved profile; see [CarProfile.verified]. */
    val verified: Boolean get() = custom?.verified ?: SurfaceProfile.active.hardwareValidated
}
