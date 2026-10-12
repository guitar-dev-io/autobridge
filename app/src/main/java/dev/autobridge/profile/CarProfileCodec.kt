package dev.autobridge.profile

import dev.autobridge.car.CarDisplayInfo
import dev.autobridge.core.model.Insets
import org.json.JSONObject

/**
 * The shared file format for [CarProfile]: a small, versioned JSON document.
 *
 * Reading is strict because the file comes from someone else:
 * - it must say what it is ([FORMAT]) and be a version this app knows; a newer one is refused
 *   with its own reason rather than half-read;
 * - only the fields listed here are read, every number is held to a plausible range, text is
 *   trimmed of control characters and cut to [MAX_TEXT], and anything else in the file is ignored;
 * - [CarProfile.verified] is always false on the way in, and the id is the store's, not the file's;
 * - the document is size-capped ([MAX_BYTES]) before parsing.
 *
 * No field can name a URL, a package, a command or a safety setting, so an imported profile can
 * only move pixels and set a frame rate within the ranges below.
 */
object CarProfileCodec {
    const val FORMAT = "autobridge.car-profile"
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024
    const val MAX_TEXT = 80

    val DPI_RANGE = 72..640
    private val SIZE_RANGE = 200..8000
    private val INSET_RANGE = 0..2000
    private const val MAX_OFFSET = 200f
    private val FPS_RANGE = 1..60

    sealed interface Result {
        data class Ok(val profile: CarProfile) : Result
        data class Error(val reason: Reason) : Result
    }

    enum class Reason { TOO_LARGE, NOT_JSON, NOT_A_PROFILE, NEWER_VERSION, MISSING_NAME, BAD_VALUE }

    fun encode(profile: CarProfile): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("name", profile.name)
        if (profile.vehicle.isNotBlank()) put("vehicle", profile.vehicle)
        if (profile.headUnit.isNotBlank()) put("headUnit", profile.headUnit)
        put("fallbackDpi", profile.fallbackDpi)
        put("safeInsets", JSONObject().apply {
            put("left", profile.safeInsets.left)
            put("top", profile.safeInsets.top)
            put("right", profile.safeInsets.right)
            put("bottom", profile.safeInsets.bottom)
        })
        put("touchOffsetX", profile.touchOffsetX.toDouble())
        put("touchOffsetY", profile.touchOffsetY.toDouble())
        profile.preferredFps?.let { put("preferredFps", it) }
        profile.screen?.let { screen ->
            put("screen", JSONObject().apply {
                put("width", screen.width)
                put("height", screen.height)
                put("dpi", screen.dpi)
                screen.stable?.let { put("stable", area(it)) }
                screen.visible?.let { put("visible", area(it)) }
            })
        }
        profile.measuredAt?.let { put("measuredAt", it) }
        profile.appVersion?.let { put("appVersion", it) }
    }.toString(2)

    /** Parses a shared file into a profile with [id]; see the class note for what is refused. */
    fun decode(text: String, id: String): Result {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Result.Error(Reason.TOO_LARGE)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return Result.Error(Reason.NOT_JSON)
        if (json.optString("format") != FORMAT) return Result.Error(Reason.NOT_A_PROFILE)
        val version = json.optInt("version", -1)
        if (version > VERSION) return Result.Error(Reason.NEWER_VERSION)
        if (version < 1) return Result.Error(Reason.NOT_A_PROFILE)

        val name = text(json, "name") ?: return Result.Error(Reason.MISSING_NAME)
        return runCatching {
            Result.Ok(
                CarProfile(
                    id = id,
                    name = name,
                    vehicle = text(json, "vehicle").orEmpty(),
                    headUnit = text(json, "headUnit").orEmpty(),
                    fallbackDpi = int(json, "fallbackDpi", DPI_RANGE) ?: 160,
                    safeInsets = json.optJSONObject("safeInsets")?.let { insets ->
                        Insets(
                            left = int(insets, "left", INSET_RANGE) ?: 0,
                            top = int(insets, "top", INSET_RANGE) ?: 0,
                            right = int(insets, "right", INSET_RANGE) ?: 0,
                            bottom = int(insets, "bottom", INSET_RANGE) ?: 0
                        )
                    } ?: Insets.ZERO,
                    touchOffsetX = offset(json, "touchOffsetX"),
                    touchOffsetY = offset(json, "touchOffsetY"),
                    preferredFps = int(json, "preferredFps", FPS_RANGE),
                    screen = json.optJSONObject("screen")?.let(::screen),
                    measuredAt = text(json, "measuredAt"),
                    appVersion = text(json, "appVersion"),
                    verified = false
                )
            )
        }.getOrElse { Result.Error(Reason.BAD_VALUE) }
    }

    private fun screen(json: JSONObject): CarProfile.Screen? {
        val width = int(json, "width", SIZE_RANGE) ?: return null
        val height = int(json, "height", SIZE_RANGE) ?: return null
        val dpi = int(json, "dpi", DPI_RANGE) ?: return null
        return CarProfile.Screen(
            width, height, dpi,
            stable = json.optJSONObject("stable")?.let { area(it, width, height) },
            visible = json.optJSONObject("visible")?.let { area(it, width, height) }
        )
    }

    private fun area(area: CarDisplayInfo.Area) = JSONObject().apply {
        put("left", area.left)
        put("top", area.top)
        put("right", area.right)
        put("bottom", area.bottom)
    }

    /** An area inside the screen with a real size; anything else is dropped, not clamped. */
    private fun area(json: JSONObject, width: Int, height: Int): CarDisplayInfo.Area? {
        val left = int(json, "left", 0..width) ?: return null
        val top = int(json, "top", 0..height) ?: return null
        val right = int(json, "right", 0..width) ?: return null
        val bottom = int(json, "bottom", 0..height) ?: return null
        return CarDisplayInfo.Area(left, top, right, bottom).takeIf { right > left && bottom > top }
    }

    /** A whole number in [range], or null when absent, fractional or out of range. */
    private fun int(json: JSONObject, key: String, range: IntRange): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? Number ?: return null
        val asDouble = value.toDouble()
        if (asDouble != Math.floor(asDouble) || asDouble.isInfinite()) return null
        return asDouble.toInt().takeIf { it in range }
    }

    private fun offset(json: JSONObject, key: String): Float {
        val value = (json.opt(key) as? Number)?.toFloat() ?: return 0f
        return if (value.isFinite() && kotlin.math.abs(value) <= MAX_OFFSET) value else 0f
    }

    private fun text(json: JSONObject, key: String): String? =
        (json.opt(key) as? String)
            ?.replace(Regex("\\p{Cntrl}"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TEXT)
            ?.takeIf { it.isNotEmpty() }
}
