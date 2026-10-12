package dev.autobridge.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * The programme guide behind channel rows: what is on now, and what is on next.
 *
 * Two kinds of source answer it:
 *
 *  - **Xtream portals** answer `get_short_epg` per channel with its next few listings, so only the
 *    rows in view are asked, a few at a time.
 *  - **M3U playlists** name an XMLTV file in their `#EXTM3U` header (`x-tvg-url`) and tie each
 *    channel to it with `tvg-id`. That file covers every channel for a week, so it is downloaded
 *    once, streamed through [XmlTvParser] keeping a day and a half around now, and kept on disk —
 *    the head unit's process comes and goes far more often than the guide changes. Which files to
 *    fetch, and how a channel is found in one, are [IptvGuideRules]' decisions.
 *
 * Everything here is best-effort. A guide that is missing, late, malformed or a 404 page costs the
 * rows their guide line and nothing else; a failure is remembered for a while so it is not retried
 * on every repaint. Callbacks land on the main thread.
 */
object IptvGuide {
    private const val TAG = "IptvGuide"
    private const val LIVE_PREFIX = "live:"

    /** Per-channel short guides go stale as programmes end; ten minutes keeps "now" honest. */
    private const val SHORT_FRESHNESS_MS = 10 * 60 * 1000L

    /** A downloaded XMLTV file is re-fetched after this; the disk copy covers the gap. */
    private const val XMLTV_FRESHNESS_MS = 6 * 60 * 60 * 1000L

    /** A guide that failed is not asked again for this long. */
    private const val RETRY_AFTER_FAILURE_MS = 30 * 60 * 1000L

    /** What of a guide is kept: a little behind now, and long enough ahead to outlast a drive. */
    private const val KEEP_BEHIND_MS = 3 * 60 * 60 * 1000L
    private const val KEEP_AHEAD_MS = 36 * 60 * 60 * 1000L

    /** Bigger than this is a catalogue of every country, not a guide anyone meant to download. */
    private const val MAX_XMLTV_BYTES = 40L * 1024 * 1024
    private const val MAX_SHORT_BODY = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "AutoBridge/1.0 (Android)"
    private const val DISK_DIR = "iptv-guide"
    private const val DISK_MAGIC = "XMLTV1"

    private data class Stamped<T>(val value: T, val atMs: Long, val failed: Boolean = false)

    private val shortGuides = ConcurrentHashMap<String, Stamped<List<IptvProgramme>>>()
    private val xmlGuides = ConcurrentHashMap<String, Stamped<XmlTvParser.Guide>>()

    /** Callers waiting on each in-flight key, so a second screen asking joins the first request. */
    private val waiting = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    private val shortWorker = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "AutoBridgeEpg").apply { isDaemon = true }
    }

    /** One guide file at a time: they are large, and two at once only halve each other's speed. */
    private val xmlWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutoBridgeXmltv").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Now and next for [entry], from what is already loaded; null when nothing is known yet. */
    fun nowNext(source: IptvSource, entry: IptvEntry, nowMs: Long = System.currentTimeMillis()): IptvNowNext? {
        if (entry.type != IptvEntryType.LIVE) return null
        val programmes = shortKey(source, entry)?.let { shortGuides[it]?.value }
            ?: xmlProgrammes(source, entry)
        return programmes?.let { IptvGuideRules.nowNext(it, nowMs) }?.takeUnless { it.isEmpty }
    }

    /**
     * The live channels among [entries] whose programme on now mentions [query]: "football" finds
     * the channels showing a match, not just the ones with "football" in their name. Only guides
     * already loaded are searched; this never fetches.
     */
    fun onNowMatching(source: IptvSource, entries: List<IptvEntry>, query: String, limit: Int): List<IptvEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val nowMs = System.currentTimeMillis()
        return entries.asSequence()
            .filter { it.type == IptvEntryType.LIVE }
            .filter { nowNext(source, it, nowMs)?.now?.title?.contains(needle, ignoreCase = true) == true }
            .take(limit)
            .toList()
    }

    /**
     * Fetches whatever guide [entries] need and is not fresh: a short guide per Xtream channel,
     * or the playlist's XMLTV files. [onUpdate] runs on the main thread each time an answer lands.
     */
    fun load(context: Context, source: IptvSource, entries: List<IptvEntry>, onUpdate: () -> Unit) {
        val live = entries.filter { it.type == IptvEntryType.LIVE }
        if (live.isEmpty()) return
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        val credentials = source.credentials
        val (portalChannels, playlistChannels) = live.partition { shortKey(source, it) != null }
        if (credentials != null) portalChannels.forEach { entry ->
            val key = shortKey(source, entry) ?: return@forEach
            val hit = shortGuides[key]
            if (hit != null && now - hit.atMs < SHORT_FRESHNESS_MS) return@forEach
            if (!join(key, onUpdate)) return@forEach
            val streamId = entry.id.removePrefix(LIVE_PREFIX)
            shortWorker.execute {
                val programmes = runCatching {
                    IptvGuideRules.parseShortEpg(
                        fetchText(credentials.apiUrl("get_short_epg", mapOf("stream_id" to streamId, "limit" to "4")))
                    )
                }.getOrDefault(emptyList())
                shortGuides[key] = Stamped(programmes, System.currentTimeMillis())
                release(key, notify = programmes.isNotEmpty())
            }
        }

        if (playlistChannels.isEmpty()) return
        guideUrlsFor(source, playlistChannels).forEach { url ->
            val hit = xmlGuides[url]
            val stale = hit == null ||
                now - hit.atMs > if (hit.failed) RETRY_AFTER_FAILURE_MS else XMLTV_FRESHNESS_MS
            if (!stale) return@forEach
            if (!join(url, onUpdate)) return@forEach
            xmlWorker.execute {
                var notify = false
                // The disk copy first: it answers instantly, and a fresh one saves the download.
                if (hit == null) {
                    readDisk(appContext, url)?.let { disk ->
                        xmlGuides[url] = disk
                        notify = !disk.value.isEmpty
                        if (System.currentTimeMillis() - disk.atMs < XMLTV_FRESHNESS_MS) {
                            release(url, notify)
                            return@execute
                        }
                    }
                }
                val fetched = runCatching { download(url) }
                    .onFailure { Log.w(TAG, "guide unavailable: ${it.message}") }
                    .getOrNull()
                if (fetched != null && !fetched.isEmpty) {
                    val stamped = Stamped(fetched, System.currentTimeMillis())
                    xmlGuides[url] = stamped
                    runCatching { writeDisk(appContext, url, stamped) }
                    notify = true
                } else if (xmlGuides[url]?.value?.isEmpty != false) {
                    xmlGuides[url] = Stamped(XmlTvParser.Guide.EMPTY, System.currentTimeMillis(), failed = true)
                } else {
                    // A stale copy is still a better answer than none; try the network again later.
                    xmlGuides[url] = xmlGuides.getValue(url).copy(atMs = System.currentTimeMillis(), failed = true)
                }
                release(url, notify)
            }
        }
    }

    /** Drops every guide, in memory and on disk, for a "clear cache" action. */
    fun clearAll(context: Context) {
        shortGuides.clear()
        xmlGuides.clear()
        runCatching { File(context.cacheDir, DISK_DIR).deleteRecursively() }
    }

    private fun shortKey(source: IptvSource, entry: IptvEntry): String? =
        if (source.type == IptvSourceType.XTREAM && entry.type == IptvEntryType.LIVE && entry.id.startsWith(LIVE_PREFIX)) {
            source.id + "|" + entry.id
        } else {
            null
        }

    private fun guideUrlsFor(source: IptvSource, entries: List<IptvEntry>): List<String> =
        IptvGuideRules.guideUrls(
            override = source.guideUrl,
            declared = IptvCatalog.cached(source.id)?.guideUrls.orEmpty(),
            fallback = IptvDirectory.guideFor(source.url),
            entries = entries
        )

    /** The entry's listings from whichever loaded guide of its source files it, if any. */
    private fun xmlProgrammes(source: IptvSource, entry: IptvEntry): List<IptvProgramme>? {
        if (xmlGuides.isEmpty()) return null
        val keys = IptvGuideRules.matchKeys(entry)
        if (keys.isEmpty()) return null
        // Every guide this source could have loaded, not just the ones chosen for this entry: the
        // lookup runs per row per repaint, and a hash probe is cheaper than choosing again.
        val candidates = if (source.guideUrl.isNotBlank()) {
            listOf(source.guideUrl.trim())
        } else {
            IptvCatalog.cached(source.id)?.guideUrls.orEmpty() + IptvDirectory.guideFor(source.url)
        }
        return candidates.asSequence()
            .mapNotNull { xmlGuides[it]?.value }
            .map { it.programmesFor(keys) }
            .firstOrNull { it.isNotEmpty() }
    }

    /** Adds [callback] to [key]'s waiters; true when the caller should start the request. */
    private fun join(key: String, callback: () -> Unit): Boolean {
        synchronized(waiting) {
            val list = waiting[key]
            if (list != null) {
                list += callback
                return false
            }
            waiting[key] = mutableListOf(callback)
            return true
        }
    }

    private fun release(key: String, notify: Boolean) {
        val callbacks = synchronized(waiting) { waiting.remove(key) }.orEmpty()
        if (notify) main.post { callbacks.forEach { it() } }
    }

    private fun download(url: String): XmlTvParser.Guide {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > MAX_XMLTV_BYTES) error("guide too large")
            val raw = BufferedInputStream(Capped(connection.inputStream, MAX_XMLTV_BYTES))
            // Decided by the bytes, not the name: servers send .xml.gz as plain XML and vice versa.
            raw.mark(2)
            val gzipped = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val stream: InputStream = if (gzipped) GZIPInputStream(raw, 64 * 1024) else raw
            stream.use {
                val parser = Xml.newPullParser()
                parser.setInput(it, null)
                val now = System.currentTimeMillis()
                return XmlTvParser.parse(parser, now - KEEP_BEHIND_MS, now + KEEP_AHEAD_MS)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().readText()
            if (body.length > MAX_SHORT_BODY) error("guide too large")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** Stops reading past [limit] bytes, whatever the server's Content-Length claimed. */
    private class Capped(stream: InputStream, private val limit: Long) : FilterInputStream(stream) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) counted(it) }

        private fun counted(bytes: Int) {
            count += bytes
            if (count > limit) throw IOException("guide too large")
        }
    }

    // ----- Disk copy: one small tab-separated file per guide URL, holding the kept window. -----

    private fun diskFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val name = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DISK_DIR), "$name.tsv")
    }

    private fun writeDisk(context: Context, url: String, stamped: Stamped<XmlTvParser.Guide>) {
        val file = diskFile(context, url)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.bufferedWriter().use { out ->
            out.write("$DISK_MAGIC\t${stamped.atMs}\n")
            stamped.value.programmes.forEach { (channel, list) ->
                list.forEach { p ->
                    out.write("P\t${clean(channel)}\t${p.startMs}\t${p.stopMs}\t${clean(p.title)}\t${clean(p.category)}\n")
                }
            }
            stamped.value.names.forEach { (key, id) -> out.write("N\t${clean(key)}\t${clean(id)}\n") }
        }
        if (!temp.renameTo(file)) temp.delete()
    }

    private fun readDisk(context: Context, url: String): Stamped<XmlTvParser.Guide>? {
        val file = diskFile(context, url)
        if (!file.exists()) return null
        return runCatching {
            file.bufferedReader().use { reader ->
                val header = reader.readLine()?.split('\t') ?: return null
                if (header.size != 2 || header[0] != DISK_MAGIC) return null
                val atMs = header[1].toLong()
                val programmes = HashMap<String, MutableList<IptvProgramme>>()
                val names = HashMap<String, String>()
                val cutoff = System.currentTimeMillis() - KEEP_BEHIND_MS
                reader.lineSequence().forEach { line ->
                    val parts = line.split('\t')
                    when {
                        parts.size == 6 && parts[0] == "P" -> {
                            val stop = parts[3].toLong()
                            if (stop > cutoff) {
                                programmes.getOrPut(parts[1]) { mutableListOf() } +=
                                    IptvProgramme(parts[4], parts[2].toLong(), stop, parts[5])
                            }
                        }
                        parts.size == 3 && parts[0] == "N" -> names[parts[1]] = parts[2]
                    }
                }
                Stamped(XmlTvParser.Guide(programmes, names), atMs)
            }
        }.getOrNull()
    }

    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
