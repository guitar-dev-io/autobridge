package dev.autobridge.media.vlc

import android.content.Context
import androidx.media3.common.Player

/**
 * The factory a build without the libVLC engine gets.
 *
 * `autobridge.vlc=false` drops the org.videolan.android:libvlc-all artifact from the dependency
 * list, which also drops `src/vlc` and the one class that imports `org.videolan.libvlc`; this
 * stands in for the `src/vlc` copy so [dev.autobridge.media.MediaPlaybackService] references one
 * stable `VlcEngineFactory.create` symbol regardless of flag state and compiles unchanged.
 *
 * Nothing reaches here in practice: [dev.autobridge.settings.VideoSettings.videoEngine] ANDs the
 * same build flag first, so it can only ever return `MEDIA3` in this build, and the service's
 * engine `when` never takes the VLC branch. Unlike the subtitle passthrough factory it throws
 * rather than returning a stub, because there is no "do nothing" Player to hand back and the only
 * way to arrive here is a build-configuration mistake, which should fail loudly rather than
 * silently fall back.
 */
object VlcEngineFactory {
    fun create(context: Context): Player {
        // Reference the parameter so this stays signature-compatible with the real factory.
        context.applicationContext
        throw IllegalStateException(
            "VlcEngineFactory.create called in a build compiled without autobridge.vlc; " +
                "VideoSettings.videoEngine() should have returned MEDIA3."
        )
    }
}
