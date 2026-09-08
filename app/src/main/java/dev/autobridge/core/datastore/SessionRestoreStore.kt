package dev.autobridge.core.datastore

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.apps.SmartMode
import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode

data class SessionSnapshot(
    val packageName: String,
    val profileId: String,
    val smartMode: SmartMode,
    val feature: Feature,
    val scaleMode: ScaleMode,
    val rotationMode: RotationMode,
    val audioMode: AudioMode,
    val fullscreen: Boolean
)

/** Pure tolerant enum decoding used by the durable session store and its JVM tests. */
object SessionRestoreCodec {
    fun smartMode(value: String?): SmartMode = decode(value, SmartMode.NATIVE_CAR)
    fun feature(value: String?, fallback: Feature): Feature = decode(value, fallback)
    fun scaleMode(value: String?): ScaleMode = decode(value, ScaleMode.FIT)
    fun rotationMode(value: String?): RotationMode = decode(value, RotationMode.AUTO)
    fun audioMode(value: String?): AudioMode = decode(value, AudioMode.MEDIA)

    private inline fun <reified T : Enum<T>> decode(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

object SessionRestoreStore {
    private const val PREFS_NAME = "autobridge_session_restore"
    private const val KEY_PACKAGE = "package"
    private const val KEY_PROFILE = "profile"
    private const val KEY_MODE = "mode"
    private const val KEY_FEATURE = "feature"
    private const val KEY_SCALE = "scale"
    private const val KEY_ROTATION = "rotation"
    private const val KEY_AUDIO = "audio"
    private const val KEY_FULLSCREEN = "fullscreen"

    fun save(context: Context, packageName: String, profile: AppProfile, smartMode: SmartMode) {
        if (packageName.isBlank()) return
        prefs(context).edit {
            putString(KEY_PACKAGE, packageName)
            putString(KEY_PROFILE, profile.profileId)
            putString(KEY_MODE, smartMode.name)
            putString(KEY_FEATURE, featureFor(smartMode).name)
            putString(KEY_SCALE, profile.scaleMode.name)
            putString(KEY_ROTATION, profile.rotationMode.name)
            putString(KEY_AUDIO, profile.audioMode.name)
            putBoolean(KEY_FULLSCREEN, profile.autoFullscreen)
        }
    }

    fun restore(context: Context): SessionSnapshot? {
        val packageName = prefs(context).getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return null
        val mode = SessionRestoreCodec.smartMode(prefs(context).getString(KEY_MODE, null))
        return SessionSnapshot(
            packageName = packageName,
            profileId = prefs(context).getString(KEY_PROFILE, "default")?.takeIf { it.isNotBlank() } ?: "default",
            smartMode = mode,
            feature = SessionRestoreCodec.feature(
                prefs(context).getString(KEY_FEATURE, null),
                featureFor(mode)
            ),
            scaleMode = SessionRestoreCodec.scaleMode(prefs(context).getString(KEY_SCALE, null)),
            rotationMode = SessionRestoreCodec.rotationMode(prefs(context).getString(KEY_ROTATION, null)),
            audioMode = SessionRestoreCodec.audioMode(prefs(context).getString(KEY_AUDIO, null)),
            fullscreen = prefs(context).getBoolean(KEY_FULLSCREEN, false)
        )
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    private fun featureFor(mode: SmartMode): Feature = when (mode) {
        SmartMode.MEDIA -> Feature.MEDIA
        SmartMode.MIRROR -> Feature.MIRROR
        SmartMode.BROWSER -> Feature.BROWSER
        SmartMode.NATIVE_CAR -> Feature.QUICK_APPS
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
