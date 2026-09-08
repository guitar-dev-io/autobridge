package dev.autobridge.apps

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.ResolutionPreset
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode

interface AppProfileRepository {
    fun get(packageName: String, profileId: String = "default"): AppProfile
    fun save(profile: AppProfile)
    fun reset(packageName: String, profileId: String = "default")
}

/** Safe, useful defaults for common apps; unknown packages use a conservative FIT profile. */
object DefaultAppProfiles {
    fun forPackage(packageName: String, profileId: String = "default"): AppProfile = when {
        packageName == "com.netflix.mediaclient" -> AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = true,
            autoFullscreen = true,
            rotationMode = RotationMode.LANDSCAPE,
            scaleMode = ScaleMode.FILL,
            preferredFps = 60,
            preferredResolution = ResolutionPreset.FULL_HD_1080P,
            touchEnabled = true,
            audioMode = AudioMode.MIRROR,
            keepPhoneScreenOn = true
        )
        packageName == "com.google.android.youtube" -> AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = true,
            autoFullscreen = true,
            rotationMode = RotationMode.LANDSCAPE,
            scaleMode = ScaleMode.FILL,
            preferredFps = 60,
            preferredResolution = ResolutionPreset.FULL_HD_1080P,
            touchEnabled = true,
            audioMode = AudioMode.MIRROR,
            keepPhoneScreenOn = true
        )
        packageName == "com.google.android.apps.maps" -> AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = true,
            rotationMode = RotationMode.AUTO,
            scaleMode = ScaleMode.FIT,
            touchEnabled = true,
            audioMode = AudioMode.MIRROR
        )
        packageName == "com.spotify.music" -> AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = false,
            rotationMode = RotationMode.AUTO,
            scaleMode = ScaleMode.FIT,
            touchEnabled = false,
            audioMode = AudioMode.MEDIA,
            keepPhoneScreenOn = false
        )
        packageName == "com.android.chrome" || packageName == "com.google.android.apps.chrome" -> AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = true,
            rotationMode = RotationMode.AUTO,
            scaleMode = ScaleMode.FIT,
            touchEnabled = true,
            audioMode = AudioMode.MIRROR
        )
        else -> AppProfile(packageName = packageName, profileId = profileId)
    }
}

class SharedPreferencesAppProfileRepository(context: Context) : AppProfileRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(packageName: String, profileId: String): AppProfile {
        val prefix = prefix(packageName, profileId)
        if (!prefs.getBoolean(prefix + "exists", false)) return DefaultAppProfiles.forPackage(packageName, profileId)
        return AppProfile(
            packageName = packageName,
            profileId = profileId,
            autoMirror = prefs.getBoolean(prefix + "autoMirror", false),
            autoFullscreen = prefs.getBoolean(prefix + "autoFullscreen", false),
            rotationMode = decodeEnum(prefs.getString(prefix + "rotation", null), RotationMode.AUTO),
            scaleMode = decodeEnum(prefs.getString(prefix + "scale", null), ScaleMode.FIT),
            preferredFps = prefs.getInt(prefix + "fps", 0).takeIf { it > 0 },
            preferredResolution = decodeNullableEnum(prefs.getString(prefix + "resolution", null)),
            touchEnabled = prefs.getBoolean(prefix + "touch", true),
            audioMode = decodeEnum(prefs.getString(prefix + "audio", null), AudioMode.MEDIA),
            keepPhoneScreenOn = prefs.getBoolean(prefix + "screenOn", true)
        )
    }

    override fun save(profile: AppProfile) {
        val prefix = prefix(profile.packageName, profile.profileId)
        prefs.edit {
            putBoolean(prefix + "exists", true)
            putBoolean(prefix + "autoMirror", profile.autoMirror)
            putBoolean(prefix + "autoFullscreen", profile.autoFullscreen)
            putString(prefix + "rotation", profile.rotationMode.name)
            putString(prefix + "scale", profile.scaleMode.name)
            putInt(prefix + "fps", profile.preferredFps ?: 0)
            putString(prefix + "resolution", profile.preferredResolution?.name)
            putBoolean(prefix + "touch", profile.touchEnabled)
            putString(prefix + "audio", profile.audioMode.name)
            putBoolean(prefix + "screenOn", profile.keepPhoneScreenOn)
        }
    }

    override fun reset(packageName: String, profileId: String) {
        val prefix = prefix(packageName, profileId)
        prefs.edit {
            listOf(
                "exists", "autoMirror", "autoFullscreen", "rotation", "scale", "fps",
                "resolution", "touch", "audio", "screenOn"
            ).forEach { remove(prefix + it) }
        }
    }

    private fun prefix(packageName: String, profileId: String): String =
        "profile:$profileId:$packageName:"

    private inline fun <reified T : Enum<T>> decodeEnum(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private inline fun <reified T : Enum<T>> decodeNullableEnum(value: String?): T? =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } }

    private companion object {
        const val PREFS_NAME = "autobridge_app_profiles"
    }
}
