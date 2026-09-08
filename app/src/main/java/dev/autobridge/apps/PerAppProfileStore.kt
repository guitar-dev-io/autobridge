package dev.autobridge.apps

import android.content.Context
import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.RotationMode

/** Compatibility facade over the full persistent AppProfile repository. */
object PerAppProfileStore {
    fun profile(context: Context, packageName: String, profileId: String = "default"): AppProfile =
        SharedPreferencesAppProfileRepository(context).get(packageName, profileId)

    fun save(context: Context, profile: AppProfile) {
        SharedPreferencesAppProfileRepository(context).save(profile)
    }

    fun reset(context: Context, packageName: String, profileId: String = "default") {
        SharedPreferencesAppProfileRepository(context).reset(packageName, profileId)
    }

    fun isForceLandscape(
        context: Context,
        packageName: String,
        profileId: String = "default"
    ): Boolean = profile(context, packageName, profileId).rotationMode == RotationMode.LANDSCAPE

    fun setForceLandscape(
        context: Context,
        packageName: String,
        forceLandscape: Boolean,
        profileId: String = "default"
    ) {
        val current = profile(context, packageName, profileId)
        save(
            context,
            current.copy(
                rotationMode = if (forceLandscape) RotationMode.LANDSCAPE else RotationMode.AUTO
            )
        )
    }

    fun toggleForceLandscape(
        context: Context,
        packageName: String,
        profileId: String = "default"
    ): Boolean {
        val newState = !isForceLandscape(context, packageName, profileId)
        setForceLandscape(context, packageName, newState, profileId)
        return newState
    }
}
