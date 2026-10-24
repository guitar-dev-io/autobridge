package dev.autobridge.desktop.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import dev.autobridge.desktop.DesktopLog
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The guest's `/etc/resolv.conf`, built from the phone's active network.
 *
 * The network's own servers go first (split-horizon and captive portals keep working), and the
 * public [FALLBACK_SERVERS] are always appended: glibc and Chrome's built-in resolver move to the
 * next server after a timeout, so a server from a network the phone has since left costs about
 * two seconds instead of breaking every lookup. Written at install, at every session start and on
 * every default-network change ([GuestDnsWatcher]).
 */
object GuestNetworkConfig {
    /** glibc reads at most this many nameserver lines (MAXNS). */
    const val MAX_NAMESERVERS = 3
    val FALLBACK_SERVERS = listOf("8.8.8.8", "1.1.1.1")
    const val OPTIONS = "options timeout:2 attempts:2"
    const val HEADER = "# Written by AutoBridge from the phone's active network; rewritten on every start and network change."
    private const val TMP_NAME = ".resolv.conf.autobridge.tmp"

    /**
     * The nameserver literals, in order, at most [MAX_NAMESERVERS]: usable network servers (IPv4
     * first; IPv6 only when [ipv6Routable]) then the fallbacks, which are always present.
     */
    fun nameservers(dnsServers: List<InetAddress>, ipv6Routable: Boolean): List<String> {
        val usable = dnsServers
            .filter { usable(it) && (it is Inet4Address || (it is Inet6Address && ipv6Routable)) }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
        val missing = FALLBACK_SERVERS.filterNot { it in usable }
        return usable.take(MAX_NAMESERVERS - missing.size) + missing
    }

    /** The whole file; no timestamp, so the same input always gives the same bytes. */
    fun resolvConf(dnsServers: List<InetAddress>, ipv6Routable: Boolean): String = buildString {
        append(HEADER).append('\n')
        nameservers(dnsServers, ipv6Routable).forEach { append("nameserver ").append(it).append('\n') }
        append(OPTIONS).append('\n')
    }

    /**
     * IPv6 servers are used only when the network has an IPv6 default route and a global address:
     * the guest shares the app's network, so only then can Chrome reach them.
     */
    fun resolvConfFor(lp: LinkProperties?): String {
        if (lp == null) return resolvConf(emptyList(), false)
        val ipv6Routable = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address } &&
            lp.linkAddresses.any { a -> a.address.let { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress } }
        return resolvConf(lp.dnsServers, ipv6Routable)
    }

    /**
     * Writes `etc/resolv.conf` under [rootfs] atomically (temp file + rename) and only when the
     * content differs. Never follows a symlink there: rename replaces the link itself. False when
     * nothing changed or the rootfs has no `etc` directory.
     */
    @Synchronized
    fun write(rootfs: File, content: String): Boolean {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) return false
        val target = File(etc, "resolv.conf")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (!Files.isSymbolicLink(target.toPath()) && target.isFile && target.readBytes().contentEquals(bytes)) return false
        val tmp = File(etc, TMP_NAME)
        try {
            Files.deleteIfExists(tmp.toPath())
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return true
    }

    /** Rewrites the file from the active network; never throws. True when the file changed. */
    fun refresh(context: Context, rootfs: File): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        update(rootfs, cm.getLinkProperties(cm.activeNetwork))
    }.getOrElse { DesktopLog.w("guest DNS refresh failed", it); false }

    /** Writes the file for [lp] and logs the servers when it changed. */
    internal fun update(rootfs: File, lp: LinkProperties?): Boolean {
        val content = resolvConfFor(lp)
        val changed = write(rootfs, content)
        if (changed) {
            val servers = content.lineSequence().filter { it.startsWith("nameserver ") }.joinToString(" ") { it.removePrefix("nameserver ") }
            DesktopLog.i("guest DNS: $servers")
        }
        return changed
    }

    private fun usable(a: InetAddress): Boolean =
        !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && !a.isMulticastAddress
}
