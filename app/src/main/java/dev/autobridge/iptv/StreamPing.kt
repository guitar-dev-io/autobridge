package dev.autobridge.iptv

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * Whether a channel address answers, and how fast.
 *
 * A public playlist is a list of addresses, not a list of working channels: a share of every list
 * is retired, geo-blocked or behind a token that expired, and the only way the user finds out is a
 * player that spins and fails. That is a bad thing to discover while driving, so a channel can be
 * checked before it is opened, and the row then says `142 ms`, `HTTP 403` or `No answer`.
 *
 * This is deliberately *not* ICMP. An app cannot open a raw socket without root, and an echo reply
 * from a CDN edge says nothing about whether the stream behind it is being served. Instead:
 *
 *  - an http(s) address gets one `GET` with `Range: bytes=0-1`, and the response status is read
 *    without touching the body — which is exactly the first thing the player would do, so a 403 on
 *    a token URL or a 404 on a retired channel is seen for what it is;
 *  - any other scheme the format allows (`rtmp`, `rtsp`) gets a TCP connect to its host and port,
 *    because that is all a non-HTTP endpoint will tell us cheaply;
 *  - a multicast or malformed address is reported as not checkable rather than guessed at.
 *
 * Results are cached process-wide for [FRESHNESS_MS] so re-rendering a page, or crossing from the
 * phone to the head unit, does not re-probe anything. Callbacks always land on the main thread.
 */
object StreamPing {
    /** What one address answered. */
    sealed interface Result {
        /** The server answered something a player can start on; [millis] is the round trip. */
        data class Alive(val millis: Long) : Result

        /** Reached, and it said no: the usual 403 on an expired token, 404 on a dead channel. */
        data class Refused(val status: Int, val millis: Long) : Result

        /** Nothing answered: DNS, no route, or past the timeout. [reason] is already readable. */
        data class Unreachable(val reason: String) : Result

        /** Nothing to probe — a multicast or malformed address no TCP connect describes. */
        object Unsupported : Result
    }

    /**
     * How a result should read, without naming a colour: a surface maps these onto its own.
     *
     * [SLOW] is the middle reading, and not a failure: a channel that answered but took over a
     * second to do it will take its time starting, which is worth seeing before tapping it rather
     * than after. An address that cannot be probed at all reads the same way — it is neither an
     * answer nor a refusal.
     */
    enum class Tone { GOOD, SLOW, BAD }

    /** How far a [checkAll] run has got; [done] marks the last call of that run. */
    data class Progress(val checked: Int, val total: Int, val alive: Int, val done: Boolean)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** Long enough that browsing a catalog never re-probes, short enough to stay true. */
    private const val FRESHNESS_MS = 5L * 60L * 1000L

    /**
     * One re-render per batch of results, not one per result: a page of 90 channels would
     * otherwise repaint 90 times, and a head unit's host rejects templates pushed that fast.
     */
    private const val PROGRESS_INTERVAL_MS = 800L

    /**
     * How long a whole run may take before it closes and reports what it has. Generous enough that
     * a page of slow-but-working channels still finishes honestly on a 4-thread pool, short enough
     * that a page is never left without an answer.
     */
    private const val RUN_DEADLINE_MS = 30_000L

    /** Four at a time keeps a page check under a few seconds without flooding one provider. */
    private const val WORKERS = 4

    /** The one reason text written by the run deadline as well as by a socket timeout. */
    private const val TIMED_OUT = "Timed out"

    /** A whole catalog never needs to be remembered; the user checks pages, not accounts. */
    private const val MAX_RESULTS = 2_000

    /** Above this a channel answered, but slowly enough that the user should know. */
    private const val SLOW_MS = 1_000L

    private const val USER_AGENT = "AutoBridge/1.0 (Android)"

    private val worker = Executors.newFixedThreadPool(WORKERS) { runnable ->
        Thread(runnable, "stream-ping").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val results = ConcurrentHashMap<String, Stamped>()

    /**
     * Addresses being probed right now, so two surfaces checking the same page do not each pay for
     * the request. This replaced a single "a run is in flight" flag, which gated *all* checking in
     * the process and could be left held for good by one stalled probe.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private data class Stamped(val result: Result, val atMs: Long)

    /** The remembered result for [url] while it is still fresh, else null. */
    fun cached(url: String): Result? {
        val stamped = results[url.trim()] ?: return null
        if (System.currentTimeMillis() - stamped.atMs > FRESHNESS_MS) return null
        return stamped.result
    }

    /**
     * Checks one address. A fresh cached result is handed back without a probe; [onResult] is
     * called on the main thread either way, and this never blocks a [checkAll] run already going.
     */
    fun check(url: String, onResult: (Result) -> Unit) {
        val address = url.trim()
        if (address.isEmpty()) {
            main.post { onResult(Result.Unsupported) }
            return
        }
        cached(address)?.let { fresh ->
            main.post { onResult(fresh) }
            return
        }
        worker.execute {
            val result = probed(address)
            main.post { onResult(result) }
        }
    }

    /**
     * Checks every address in [urls], skipping the ones already answered.
     *
     * [onProgress] is called on the main thread, coalesced to [PROGRESS_INTERVAL_MS], and always
     * exactly once with `done = true`; the per-row results are read back through [cached], which
     * is what lets a surface simply re-render itself.
     *
     * **A run always ends.** It used to end only when every probe had reported, which made one
     * stalled socket fatal: the probe pool is small, a host that resolves slowly or accepts and
     * then says nothing holds a thread for far longer than its timeouts suggest, and the run that
     * never finished left a flag held that refused every later check in the process. The page then
     * showed no results, "Check again" did nothing, and only restarting the app brought checking
     * back. So a run now closes on [RUN_DEADLINE_MS] whatever its stragglers are doing, and the
     * addresses that did not answer in time are recorded as such — a row that says "Timed out" is
     * worth more than a row that stays blank for good. A straggler that lands later simply
     * overwrites its own entry with the truth.
     */
    fun checkAll(urls: List<String>, onProgress: (Progress) -> Unit) {
        val pending = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val total = pending.size
        if (total == 0) {
            main.post { onProgress(Progress(0, 0, 0, done = true)) }
            return
        }

        val checked = AtomicInteger()
        val alive = AtomicInteger()
        val posted = AtomicBoolean(false)
        val finished = AtomicBoolean(false)

        fun finish(timedOut: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            if (timedOut) {
                pending.forEach { address ->
                    if (cached(address) == null) remember(address, Result.Unreachable(TIMED_OUT))
                }
            }
            val seen = checked.get().coerceAtMost(total)
            main.post { onProgress(Progress(seen, total, alive.get(), done = true)) }
        }

        fun report(count: Int) {
            if (count >= total) {
                finish(timedOut = false)
                return
            }
            if (posted.getAndSet(true)) return
            main.postDelayed({
                posted.set(false)
                val seen = checked.get()
                if (!finished.get() && seen < total) {
                    onProgress(Progress(seen, total, alive.get(), done = false))
                }
            }, PROGRESS_INTERVAL_MS)
        }

        main.postDelayed({ finish(timedOut = true) }, RUN_DEADLINE_MS)

        pending.forEach { address ->
            val fresh = cached(address)
            if (fresh != null) {
                if (fresh is Result.Alive) alive.incrementAndGet()
                report(checked.incrementAndGet())
                return@forEach
            }
            // Another run is already probing this address; its answer lands in the same cache, so
            // this one counts it rather than paying for a second request.
            if (!inFlight.add(address)) {
                report(checked.incrementAndGet())
                return@forEach
            }
            worker.execute {
                val result = try {
                    probed(address)
                } finally {
                    inFlight.remove(address)
                }
                if (result is Result.Alive) alive.incrementAndGet()
                report(checked.incrementAndGet())
            }
        }
    }

    /** The row-sized reading of [result]: what the phone writes into a subtitle. */
    fun describe(result: Result): String = when (result) {
        is Result.Alive -> "${result.millis} ms"
        is Result.Refused -> "HTTP ${result.status}"
        is Result.Unreachable -> result.reason
        Result.Unsupported -> "Not checkable"
    }

    /** Which of the three readings [result] is; the surfaces own the colours. */
    fun tone(result: Result): Tone = when (result) {
        is Result.Alive -> if (result.millis > SLOW_MS) Tone.SLOW else Tone.GOOD
        is Result.Refused -> Tone.BAD
        is Result.Unreachable -> Tone.BAD
        // Neither an answer nor a failure, so it takes the middle colour rather than the red one.
        Result.Unsupported -> Tone.SLOW
    }

    /**
     * Forgets what was answered about [urls], so the next check really probes them again.
     *
     * This is what makes an explicit "check again" mean something: without it a tap inside the
     * freshness window would hand back the same cached answers and look like it did nothing.
     */
    fun forget(urls: List<String>) {
        urls.forEach { results.remove(it.trim()) }
    }

    /** Drops every remembered result, so a "check again" really checks again. */
    fun clear() = results.clear()

    /** [probe] plus its own failure, remembered. Never throws, whatever the address does. */
    private fun probed(url: String): Result {
        val result = runCatching { probe(url) }.getOrElse { Result.Unreachable(reason(it)) }
        runCatching { remember(url, result) }
        return result
    }

    private fun probe(url: String): Result {
        val endpoint = endpoint(url) ?: return Result.Unsupported
        return if (endpoint.httpLike) httpProbe(url) else tcpProbe(endpoint)
    }

    private fun httpProbe(url: String): Result {
        val started = System.nanoTime()
        val connection = runCatching { URL(url).openConnection() as? HttpURLConnection }
            .getOrNull() ?: return Result.Unsupported
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // Not followed: a 3xx already classifies as alive ([classify]), and a provider that
        // redirects through a chain of token hosts would otherwise multiply the timeouts by its
        // length - the one way a single probe can outlast everything configured here.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        // Two bytes is enough to prove the server will serve this address; one that ignores Range
        // answers 200 and the body is dropped unread either way, so nothing is downloaded.
        connection.setRequestProperty("Range", "bytes=0-1")
        return try {
            val status = connection.responseCode
            classify(status, elapsedMs(started))
        } catch (error: Throwable) {
            Result.Unreachable(reason(error))
        } finally {
            connection.disconnect()
        }
    }

    private fun tcpProbe(endpoint: Endpoint): Result {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
                Result.Alive(elapsedMs(started)) as Result
            }
        }.getOrElse { Result.Unreachable(reason(it)) }
    }

    /** Which status a player can start on. Redirects are followed, so a 3xx here is a rarity. */
    internal fun classify(status: Int, millis: Long): Result =
        if (status in 200..399) Result.Alive(millis) else Result.Refused(status, millis)

    /** The host and port to probe, and whether HTTP is spoken there. */
    internal data class Endpoint(val host: String, val port: Int, val httpLike: Boolean)

    /**
     * [url] split into something connectable, or null when nothing here can be probed: a bare
     * path, or a multicast address (`udp://`, `rtp://`) that answers no connection at all.
     */
    internal fun endpoint(url: String): Endpoint? {
        val trimmed = url.trim()
        val separator = trimmed.indexOf("://")
        if (separator <= 0) return null
        val scheme = trimmed.substring(0, separator).lowercase()
        val port = DEFAULT_PORTS[scheme] ?: return null
        val authority = trimmed.substring(separator + 3)
            .takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
        if (authority.isBlank()) return null

        // An IPv6 literal is bracketed, and splitting it on the colon would cut the address up.
        val host: String
        val explicit: String
        if (authority.startsWith("[")) {
            host = authority.substringBefore(']').removePrefix("[")
            explicit = authority.substringAfter(']').removePrefix(":")
        } else {
            host = authority.substringBefore(':')
            explicit = authority.substringAfter(':', "")
        }
        if (host.isBlank()) return null
        val resolved = explicit.toIntOrNull()?.takeIf { it in 1..65535 } ?: port
        return Endpoint(host, resolved, scheme == "http" || scheme == "https")
    }

    private fun remember(url: String, result: Result) {
        if (results.size >= MAX_RESULTS) {
            val cutoff = System.currentTimeMillis() - FRESHNESS_MS
            results.entries.removeAll { it.value.atMs < cutoff }
            // Still full of fresh results: this is a catalog-sized sweep, and the oldest of them
            // are the pages the user has already left behind.
            if (results.size >= MAX_RESULTS) results.clear()
        }
        results[url] = Stamped(result, System.currentTimeMillis())
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L

    private fun reason(error: Throwable): String = when (error) {
        is UnknownHostException -> "Unknown host"
        is SocketTimeoutException -> TIMED_OUT
        is ConnectException -> "Connection refused"
        is NoRouteToHostException -> "No route"
        is SSLException -> "TLS failed"
        is IOException -> "No answer"
        else -> error.javaClass.simpleName
    }

    /**
     * Ports for the schemes an IPTV playlist actually carries. A scheme absent from here cannot be
     * probed by connecting - `udp`/`rtp` multicast has nothing to connect to - and is reported as
     * not checkable instead of as dead, which would be a lie about a channel that may well play.
     */
    private val DEFAULT_PORTS = mapOf(
        "http" to 80,
        "https" to 443,
        "rtmp" to 1935,
        "rtmps" to 443,
        "rtsp" to 554,
        "rtsps" to 322
    )
}
