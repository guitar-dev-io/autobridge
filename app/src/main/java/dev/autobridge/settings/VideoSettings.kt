package dev.autobridge.settings

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which engine actually decodes and renders the picture — the built-in Media3/ExoPlayer path, or
 * the embedded libVLC engine, the Fermata-style "pick which engine plays your media" choice.
 *
 * Orthogonal to [PreferredPlayer]: that one chooses a decoder *within* ExoPlayer (hardware vs
 * software), while this one chooses the decode engine entirely. The VLC option exists, and is
 * selectable, only in a build compiled with `-Pautobridge.vlc=true`; see
 * [VideoSettings.videoEngine] for how a stored `VLC` falls back to [MEDIA3] when the flag is off.
 */
enum class VideoEngine(val label: String, val caption: String) {
    MEDIA3("Media3", "The built-in player"),
    VLC("VLC", "libVLC plays your media")
}

/**
 * Which decoder the shared player reaches for first.
 *
 * Media3 ranks decoders itself and usually gets it right; the override exists because a head unit
 * or a phone chip occasionally refuses one specific IPTV codec, and the only cure is to push that
 * stream onto the other kind of decoder.
 */
enum class PreferredPlayer(val label: String, val caption: String) {
    AUTO("Auto", "Media3 ranks the decoders"),
    HARDWARE("Hardware", "Hardware decoders first, software only if none fits"),
    SOFTWARE("Software", "Software decoders first, for streams a chip gets wrong")
}

/** How a frame is fitted into the player's video box. */
enum class AspectRatio(val label: String, val caption: String, val ratio: Float) {
    /** The stream's own shape, letterboxed. */
    AUTO("Auto", "Follow the stream's own shape", 0f),
    /** The stream's shape, but zoomed until the box is full; the overflow is cropped. */
    FILL("Fill", "Zoom until the box is full, cropping the overflow", 0f),
    /** Ignores the source shape entirely. */
    STRETCH("Stretch", "Stretch to the box, distorting the picture", 0f),
    WIDE("16:9", "Force widescreen", 16f / 9f),
    CLASSIC("4:3", "Force the classic shape", 4f / 3f)
}

/** The gesture that moves to the next or previous channel inside the player. */
enum class ChannelGesture(val label: String, val caption: String) {
    SWIPE_VERTICAL("Swipe up / down", "Swipe the picture vertically"),
    SWIPE_HORIZONTAL("Swipe left / right", "Swipe the picture horizontally"),
    DOUBLE_TAP("Double tap edges", "Double tap the left or right edge of the picture"),
    OFF("Off", "Only the transport buttons change channel")
}

/** How the player screen splits between the picture and the rest of the queue. */
enum class SplitLayout(val label: String, val caption: String) {
    MAIN_ONLY("Main only", "Picture and transport, nothing else"),
    MAIN_SUB("Main + Sub", "Picture above, the rest of the queue below"),
    SIDE_BY_SIDE("Side by side", "Picture beside the queue when the screen is wide")
}

/**
 * The density the player draws its own controls at.
 *
 * AutoBridge renders the player itself rather than handing the stream to a system surface, so
 * there is no external display whose DPI could be overridden. What this does change is the scale
 * of the player's own chrome, which is the part a head unit at arm's length makes too small.
 */
enum class PlayerDpi(val label: String, val caption: String, val scale: Float) {
    DEFAULT("Default", "The phone's own density", 1f),
    COMPACT("Compact", "Smaller controls, more picture", 0.85f),
    LARGE("Large", "Bigger controls, easier at arm's length", 1.25f)
}

/**
 * Settings for the phone video player ([dev.autobridge.library.PlayerActivity]) and the shared
 * Media3 player behind it.
 *
 * The same SharedPreferences `object` pattern as [AppPreferences]: every value is readable without
 * a handle to the screen that owns it, because the player, the playback service and the settings
 * screen all need them and none of the three owns the other two. Changing anything notifies
 * [addListener], which is how the already-running service picks up a new enhancement or decoder
 * choice without a restart.
 *
 * The defaults match what someone expects from a TV app: a channel keeps playing when the screen
 * is left, and the live delay is visible. Auto next channel is off, because advancing on its own
 * past a channel that merely stalled is a change to what was asked for.
 */
object VideoSettings {
    private const val PREFS_NAME = "autobridge_video"

    private const val KEY_VIDEO_ENGINE = "video_engine"
    private const val KEY_PREFERRED_PLAYER = "preferred_player"
    private const val KEY_PLAY_IN_BACKGROUND = "play_in_background"
    private const val KEY_AUTO_PIP = "auto_picture_in_picture"
    private const val KEY_AUTO_NEXT_CHANNEL = "auto_next_channel"
    private const val KEY_CHANNEL_GESTURE = "change_channel_gesture"
    private const val KEY_SPLIT_LAYOUT = "split_screen_layout"
    private const val KEY_ASPECT_RATIO = "default_aspect_ratio"
    private const val KEY_PLAYER_DPI = "preferred_dpi"
    private const val KEY_SHOW_DELAY = "show_delay"
    private const val KEY_ENHANCEMENT = "video_enhancement_enabled"
    private const val KEY_BRIGHTNESS = "video_brightness"
    private const val KEY_CONTRAST = "video_contrast"
    private const val KEY_SATURATION = "video_saturation"

    /** Enhancement sliders run over this symmetric range, with 0 meaning "leave the frame alone". */
    const val ADJUSTMENT_RANGE = 100

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called after any change here, on the thread that made it. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Which engine the playback service builds its player on.
     *
     * The build flag comes first and is not overridable from the app, the same contract as
     * [dev.autobridge.subtitles.SubtitleSettings.enabled]: a build made without `autobridge.vlc`
     * has no VLC engine compiled in, and the stored preference outlives that change — someone who
     * had picked VLC, then installed a build without it, would otherwise have the service try to
     * build an engine that is not there. ANDing the flag first makes a stale `VLC` resolve to
     * [VideoEngine.MEDIA3], so the VLC branch in the service is unreachable and `src/novlc`'s
     * throwing factory is never called.
     */
    fun videoEngine(context: Context): VideoEngine =
        if (BuildConfig.VLC_ENGINE) {
            VideoSettingsCodec.videoEngine(prefs(context).getString(KEY_VIDEO_ENGINE, null))
        } else {
            VideoEngine.MEDIA3
        }

    /** True when this build has the libVLC engine in it; see [videoEngine]. */
    val isVlcSupported: Boolean get() = BuildConfig.VLC_ENGINE

    fun setVideoEngine(context: Context, value: VideoEngine) =
        putString(context, KEY_VIDEO_ENGINE, value.name)

    fun preferredPlayer(context: Context): PreferredPlayer =
        VideoSettingsCodec.preferredPlayer(prefs(context).getString(KEY_PREFERRED_PLAYER, null))

    fun setPreferredPlayer(context: Context, value: PreferredPlayer) =
        putString(context, KEY_PREFERRED_PLAYER, value.name)

    fun playInBackground(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLAY_IN_BACKGROUND, true)

    fun setPlayInBackground(context: Context, value: Boolean) =
        putBoolean(context, KEY_PLAY_IN_BACKGROUND, value)

    fun autoPictureInPicture(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PIP, true)

    fun setAutoPictureInPicture(context: Context, value: Boolean) =
        putBoolean(context, KEY_AUTO_PIP, value)

    fun autoNextChannel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_NEXT_CHANNEL, false)

    fun setAutoNextChannel(context: Context, value: Boolean) =
        putBoolean(context, KEY_AUTO_NEXT_CHANNEL, value)

    fun channelGesture(context: Context): ChannelGesture =
        VideoSettingsCodec.channelGesture(prefs(context).getString(KEY_CHANNEL_GESTURE, null))

    fun setChannelGesture(context: Context, value: ChannelGesture) =
        putString(context, KEY_CHANNEL_GESTURE, value.name)

    fun splitLayout(context: Context): SplitLayout =
        VideoSettingsCodec.splitLayout(prefs(context).getString(KEY_SPLIT_LAYOUT, null))

    fun setSplitLayout(context: Context, value: SplitLayout) =
        putString(context, KEY_SPLIT_LAYOUT, value.name)

    fun aspectRatio(context: Context): AspectRatio =
        VideoSettingsCodec.aspectRatio(prefs(context).getString(KEY_ASPECT_RATIO, null))

    fun setAspectRatio(context: Context, value: AspectRatio) =
        putString(context, KEY_ASPECT_RATIO, value.name)

    fun playerDpi(context: Context): PlayerDpi =
        VideoSettingsCodec.playerDpi(prefs(context).getString(KEY_PLAYER_DPI, null))

    fun setPlayerDpi(context: Context, value: PlayerDpi) = putString(context, KEY_PLAYER_DPI, value.name)

    fun showDelay(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_DELAY, true)

    fun setShowDelay(context: Context, value: Boolean) = putBoolean(context, KEY_SHOW_DELAY, value)

    fun enhancementEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENHANCEMENT, false)

    fun setEnhancementEnabled(context: Context, value: Boolean) =
        putBoolean(context, KEY_ENHANCEMENT, value)

    fun brightness(context: Context): Int = adjustment(context, KEY_BRIGHTNESS)

    fun setBrightness(context: Context, value: Int) = putAdjustment(context, KEY_BRIGHTNESS, value)

    fun contrast(context: Context): Int = adjustment(context, KEY_CONTRAST)

    fun setContrast(context: Context, value: Int) = putAdjustment(context, KEY_CONTRAST, value)

    fun saturation(context: Context): Int = adjustment(context, KEY_SATURATION)

    fun setSaturation(context: Context, value: Int) = putAdjustment(context, KEY_SATURATION, value)

    /** The three sliders at once, as the playback service wants them. */
    fun enhancement(context: Context): VideoEnhancement = VideoEnhancement(
        enabled = enhancementEnabled(context),
        brightness = brightness(context),
        contrast = contrast(context),
        saturation = saturation(context)
    )

    /** Back to a frame the decoder produced, untouched. */
    fun resetEnhancement(context: Context) {
        prefs(context).edit {
            putBoolean(KEY_ENHANCEMENT, false)
            putInt(KEY_BRIGHTNESS, 0)
            putInt(KEY_CONTRAST, 0)
            putInt(KEY_SATURATION, 0)
        }
        changed()
    }

    private fun adjustment(context: Context, key: String): Int =
        prefs(context).getInt(key, 0).coerceIn(-ADJUSTMENT_RANGE, ADJUSTMENT_RANGE)

    private fun putAdjustment(context: Context, key: String, value: Int) {
        prefs(context).edit { putInt(key, value.coerceIn(-ADJUSTMENT_RANGE, ADJUSTMENT_RANGE)) }
        changed()
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit { putBoolean(key, value) }
        changed()
    }

    private fun putString(context: Context, key: String, value: String) {
        prefs(context).edit { putString(key, value) }
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * The three colour adjustments, with the conversions the GL effects want.
 *
 * Kept as plain data so the mapping from "what the slider says" to "what the shader takes" is
 * testable without a player: Media3's HSL effect works in -100..100, its contrast matrix in
 * -1..1, and a 0 on every slider has to come out as "no effect at all" rather than as an identity
 * shader the renderer still has to run.
 */
data class VideoEnhancement(
    val enabled: Boolean,
    val brightness: Int,
    val contrast: Int,
    val saturation: Int
) {
    /** True when the frame would come out exactly as decoded, so the effects can be skipped. */
    val isNeutral: Boolean
        get() = !enabled || (brightness == 0 && contrast == 0 && saturation == 0)

    /** Lightness for `HslAdjustment.adjustLightness`, which takes -100..100. */
    val lightnessAdjustment: Float get() = brightness.toFloat()

    /** Saturation for `HslAdjustment.adjustSaturation`, which takes -100..100. */
    val saturationAdjustment: Float get() = saturation.toFloat()

    /** Contrast for Media3's `Contrast`, which takes -1 (flat) to 1 (hard). */
    val contrastAdjustment: Float get() = contrast / VideoSettings.ADJUSTMENT_RANGE.toFloat()
}

/**
 * Null-safe decoding of persisted enum names, with an explicit default when the stored value is
 * missing or was renamed between versions — the same contract as [SettingsCodec], and separated
 * from [VideoSettings] for the same reason: it is testable without a [Context].
 */
object VideoSettingsCodec {
    fun videoEngine(name: String?): VideoEngine =
        VideoEngine.entries.firstOrNull { it.name == name } ?: VideoEngine.MEDIA3

    fun preferredPlayer(name: String?): PreferredPlayer =
        PreferredPlayer.entries.firstOrNull { it.name == name } ?: PreferredPlayer.AUTO

    fun channelGesture(name: String?): ChannelGesture =
        ChannelGesture.entries.firstOrNull { it.name == name } ?: ChannelGesture.SWIPE_VERTICAL

    fun splitLayout(name: String?): SplitLayout =
        SplitLayout.entries.firstOrNull { it.name == name } ?: SplitLayout.MAIN_SUB

    fun aspectRatio(name: String?): AspectRatio =
        AspectRatio.entries.firstOrNull { it.name == name } ?: AspectRatio.AUTO

    fun playerDpi(name: String?): PlayerDpi =
        PlayerDpi.entries.firstOrNull { it.name == name } ?: PlayerDpi.DEFAULT
}
