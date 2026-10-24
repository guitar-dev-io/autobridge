package dev.autobridge.desktop.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import dev.autobridge.desktop.DesktopLog
import java.io.File

/**
 * Keeps the guest's resolv.conf on the phone's current default network while a desktop session
 * exists. [start] and [stop] are idempotent; at most one callback is registered. A callback that
 * arrives after [stop] does nothing, and [stop] waits for one that is writing, so the rootfs is
 * never written once the session is gone.
 */
class GuestDnsWatcher(
    private val context: Context,
    private val rootfs: File,
    private val onChanged: () -> Unit = {},
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var thread: HandlerThread? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val t = HandlerThread("desktop-dns").apply { start() }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed(this, runCatching { cm.getLinkProperties(network) }.getOrNull())
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed(this, linkProperties)
            // onLost keeps the last file; the next default network's onAvailable rewrites it.
        }
        try {
            cm.registerDefaultNetworkCallback(cb, Handler(t.looper))
            callback = cb
            thread = t
        } catch (e: Exception) {
            DesktopLog.w("guest DNS watcher not registered", e)
            t.quitSafely()
        }
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        context.getSystemService(ConnectivityManager::class.java)?.let { cm -> runCatching { cm.unregisterNetworkCallback(cb) } }
        thread?.quitSafely()
        thread = null
    }

    private fun changed(cb: ConnectivityManager.NetworkCallback, lp: LinkProperties?) {
        if (lp == null) return
        val updated = synchronized(this) {
            if (callback !== cb) return
            runCatching { GuestNetworkConfig.update(rootfs, lp) }
                .onFailure { DesktopLog.w("guest DNS update failed", it) }
                .getOrDefault(false)
        }
        if (updated) onChanged()
    }
}
