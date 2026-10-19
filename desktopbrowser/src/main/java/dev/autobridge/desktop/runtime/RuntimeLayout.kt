package dev.autobridge.desktop.runtime

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Every path the desktop engine uses, host side and guest (inside the rootfs) side.
 *
 * Everything lives in internal storage: nothing here is readable by other apps, which matters
 * because the X display and the audio socket below are reachable by anything that can open the
 * file. They are Unix sockets in this app's private directory, never TCP ports or abstract
 * sockets, which any app on the phone could connect to.
 */
class RuntimeLayout(val base: File, val nativeLibraryDir: File, val cacheDir: File) {
    constructor(context: Context) : this(
        File(context.filesDir, "desktop-browser"),
        File(context.applicationInfo.nativeLibraryDir),
        context.cacheDir,
    )

    val rootfs = File(base, "rootfs")
    val staging = File(base, "staging")
    val downloads = File(base, "downloads")
    val logs = File(base, "logs")
    val prootTmp = File(cacheDir, "proot-tmp")

    val proot = File(nativeLibraryDir, "libproot.so")
    val prootLoader = File(nativeLibraryDir, "libproot-loader.so")

    /** Written last by the installer; a rootfs without it is an interrupted install. */
    val completeMarker = File(rootfs, ".autobridge-runtime")

    fun host(guestPath: String): File = File(rootfs, guestPath.trimStart('/'))

    val x11Socket: File get() = host("$GUEST_X11_DIR/X$DISPLAY_NUMBER")
    val x11Lock: File get() = host("/tmp/.X$DISPLAY_NUMBER-lock")
    val framebuffer: File get() = host("$GUEST_FB_DIR/Xvfb_screen0")
    val pulsePcmSocket: File get() = host("$GUEST_PULSE_DIR/pcm")
    val runDir: File get() = host(GUEST_RUN_DIR)

    fun pidFile(name: String): File = host("$GUEST_RUN_DIR/$name.pid")

    fun logFile(name: String): File = File(logs, "$name.log")

    companion object {
        const val DISPLAY_NUMBER = 1
        const val GUEST_HOME = "/home/autobridge"
        const val GUEST_X11_DIR = "/tmp/.X11-unix"
        const val GUEST_FB_DIR = "/tmp/autobridge-fb"
        const val GUEST_PULSE_DIR = "/tmp/autobridge-pulse"
        const val GUEST_RUN_DIR = "/tmp/autobridge-run"
        const val GUEST_PULSE_CONFIG = "/etc/autobridge/pulse.pa"

        /** The browsers' start page, written by the app before each launch (see DesktopEngine.startPages). */
        const val GUEST_START_DIR = "/opt/autobridge/start"
        const val START_URL = "file://$GUEST_START_DIR/index.html"

        /** The browsers this engine can install exist for arm64 Linux only. */
        fun deviceSupported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
    }
}
