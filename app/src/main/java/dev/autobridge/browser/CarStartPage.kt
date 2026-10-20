package dev.autobridge.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import dev.autobridge.BuildConfig
import dev.autobridge.R
import dev.autobridge.car.CarStationTile
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.entertainment.WebHistoryStore
import dev.autobridge.support.QrCode
import dev.autobridge.support.SupportChannels
import dev.autobridge.ui.AutoBridgeDesign
import java.io.ByteArrayOutputStream

/**
 * The car browser's start page, drawn by the browser itself the way AABrowser draws its own: a
 * dark page in the app's own colours, the title, one large search field, the support card and the quick links.
 *
 * It used to be a car GridTemplate, and the head unit draws a template its own way (dark tiles,
 * its own fonts and spacing), so no amount of tuning made it look like AABrowser. Served as a web
 * page into the car's WebView it looks the same on every head unit, and the page's search field
 * gets the car keyboard the way any site's field does.
 *
 * The page lives at a reserved address ([HOST] is the one Android sets aside for app content), so
 * it can never collide with a real site; [intercept] answers every request for it.
 */
object CarStartPage {
    const val HOST = "appassets.androidplatform.net"
    private const val BASE = "https://$HOST/autobridge"
    const val URL = "$BASE/start"
    private const val GO = "$BASE/go"
    private const val SUPPORT = "$BASE/support"
    private const val DESKTOP = "$BASE/desktop"
    private const val FORGET = "$BASE/forget"
    private const val CLEAR_RECENT = "$BASE/clear-recent"

    private const val QUICK_LINK_LIMIT = 12
    private const val RECENT_LIMIT = 4
    private const val QR_PX = 360

    fun isStartPage(url: String?): Boolean = url != null && url.substringBefore('?').substringBefore('#') == URL

    /** True for the page's own addresses, which must never be saved as a site or resumed as one. */
    fun isInternal(url: String?): Boolean = url != null && url.startsWith("$BASE/")

    /** The "desktop browser" link: the renderer hands it to the car screen instead of loading it. */
    fun isDesktopLink(url: String): Boolean = url.substringBefore('?') == DESKTOP

    /** A tile on the page: one saved site, or one page visited lately. */
    data class QuickLink(val title: String, val url: String, val host: String, val badge: String, val color: Int)

    /** Every text the page shows, read from the app's strings so it follows the app language. */
    data class Texts(
        val title: String,
        val subtitle: String,
        val searchHint: String,
        val go: String,
        val quickLinks: String,
        val supportTitle: String,
        val supportMessage: String,
        val supportAction: String,
        val desktop: String,
        val recent: String = "",
        val clear: String = "",
        val clearRecentTitle: String = "",
        val cancel: String = "",
        val remove: String = "",
    )

    /**
     * The response for a request to the page's addresses, or null for every other address (the
     * renderer then fetches it as usual). Called on the WebView's network thread.
     */
    fun intercept(context: Context, request: WebResourceRequest, desktopAvailable: Boolean): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != HOST) return null
        val path = url.toString().substringBefore('?').substringBefore('#')
        return when (path) {
            URL -> html(startHtml(texts(context), quickLinks(context), desktopAvailable, recent = recentPages(context)))
            GO -> html(redirectHtml(target(context, url)))
            SUPPORT -> html(supportHtml(texts(context), supportChannels(context)))
            FORGET -> {
                url.getQueryParameter("u")?.let { WebHistoryStore.remove(context, it) }
                html(redirectHtml(URL))
            }
            CLEAR_RECENT -> if (url.getQueryParameter("confirm") == "1") {
                WebHistoryStore.clear(context)
                html(redirectHtml(URL))
            } else {
                html(clearRecentHtml(texts(context)))
            }
            else -> if (isInternal(path)) html(redirectHtml(URL)) else null
        }
    }

    /**
     * The same page for the desktop browsers (Chrome, Brave), as files their Linux system holds:
     * file name to markup. The two pages link to each other by name.
     */
    fun desktopPages(context: Context): Map<String, String> {
        val t = texts(context)
        return mapOf(
            "index.html" to startHtml(t, quickLinks(context), desktopAvailable = false,
                searchPrefix = SearchEngineStore.engine(context).searchUrl(""), supportHref = "support.html",
                recent = recentPages(context)),
            "support.html" to supportHtml(t, supportChannels(context), backHref = "index.html"),
        )
    }

    private fun target(context: Context, url: Uri): String {
        val query = url.getQueryParameter("q")?.trim().orEmpty()
        if (query.isEmpty()) return URL
        return BrowserDefaults.resolve(query, SearchEngineStore.engine(context))
    }

    private fun texts(context: Context) = Texts(
        title = context.getString(R.string.browser_start_page_title),
        subtitle = context.getString(R.string.browser_start_page_subtitle),
        searchHint = context.getString(R.string.browser_start_page_search_hint),
        go = context.getString(R.string.browser_start_page_go),
        quickLinks = context.getString(R.string.browser_start_page_shortcuts),
        supportTitle = context.getString(R.string.support_card_title),
        supportMessage = context.getString(R.string.support_card_message),
        supportAction = context.getString(R.string.car_browser_start_support_caption),
        desktop = context.getString(R.string.car_web_open_browser),
        recent = context.getString(R.string.browser_start_page_recent),
        clear = context.getString(R.string.action_clear),
        clearRecentTitle = context.getString(R.string.car_start_clear_recent_title),
        cancel = context.getString(R.string.action_cancel),
        remove = context.getString(R.string.action_delete),
    )

    private fun quickLinks(context: Context): List<QuickLink> =
        WebBookmarkStore.list(context).take(QUICK_LINK_LIMIT).map { bookmark ->
            QuickLink(
                title = bookmark.title,
                url = bookmark.url,
                host = hostOf(bookmark.url),
                badge = CarStationTile.label(bookmark.title),
                color = CarStationTile.colorFor(bookmark.title),
            )
        }

    /**
     * The pages visited lately, newest first, as the phone start page's Recent section shows them:
     * one tile per address, and never the start page's own.
     */
    private fun recentPages(context: Context): List<QuickLink> =
        WebHistoryStore.list(context).asSequence()
            .filterNot { isInternal(it.url) }
            .distinctBy { it.url }
            .take(RECENT_LIMIT)
            .map { entry ->
                val title = entry.title.ifBlank { hostOf(entry.url) }
                QuickLink(title, entry.url, hostOf(entry.url), CarStationTile.label(title), CarStationTile.colorFor(title))
            }
            .toList()

    /** Label, line under it, and the QR as a PNG data URI, for each way to support. */
    private fun supportChannels(context: Context): List<Triple<String, String, String>> =
        SupportChannels.available(safeBuild = BuildConfig.AUTOBRIDGE_MODE == "SAFE").map { channel ->
            val bundled = QrCode.bundled(channel.kind)
            val qr = if (bundled != null) QrCode.bundledOnWhite(context, bundled, QR_PX)
            else QrCode.bitmap(channel.url.orEmpty(), QR_PX)
            Triple(channel.label, channel.displayUrl ?: context.getString(R.string.support_scan_bank_app), pngDataUri(qr))
        }

    private fun pngDataUri(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url

    private fun html(body: String) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK",
        // Rebuilt on every visit, so a site saved a moment ago is already on it.
        mapOf("Cache-Control" to "no-store"),
        body.byteInputStream(Charsets.UTF_8),
    )

    // ------------------------------------------------------------------ markup

    /**
     * The page itself. Pure, so the markup is testable without a WebView. In the car renderer the
     * search goes to [GO]; a desktop browser has no one answering that address, so there
     * [searchPrefix] (the engine's results address, the query appended) is used from the page.
     */
    fun startHtml(
        t: Texts,
        links: List<QuickLink>,
        desktopAvailable: Boolean,
        searchPrefix: String? = null,
        supportHref: String = SUPPORT,
        recent: List<QuickLink> = emptyList(),
    ): String = page(t.title, buildString {
        append("<header><h1>").append(esc(t.title)).append("</h1><p class=\"sub\">").append(esc(t.subtitle)).append("</p></header>")
        append("<form class=\"search\" action=\"").append(GO).append("\" method=\"get\" autocomplete=\"off\"")
        if (searchPrefix != null) append(" onsubmit=\"return go(this)\"")
        append(">")
        append(SEARCH_ICON)
        append("<input name=\"q\" type=\"search\" enterkeyhint=\"go\" placeholder=\"").append(esc(t.searchHint)).append("\" aria-label=\"").append(esc(t.searchHint)).append("\">")
        append("<button type=\"submit\" aria-label=\"").append(esc(t.go)).append("\">").append(ARROW_ICON).append("</button></form>")

        append("<section class=\"card\"><div class=\"card-head\"><div class=\"card-icon\">☕</div><h2>")
            .append(esc(t.supportTitle)).append("</h2></div><p>").append(esc(t.supportMessage)).append("</p>")
        append("<div class=\"actions\"><a class=\"pill\" href=\"").append(esc(supportHref)).append("\">").append(esc(t.supportAction)).append(EXTERNAL_ICON).append("</a>")
        if (desktopAvailable) {
            append("<a class=\"pill ghost\" href=\"").append(DESKTOP).append("\">").append(esc(t.desktop)).append("</a>")
        }
        append("</div></section>")

        // Recent pages, then the saved sites, as the phone start page orders them.
        // Only the car renderer can answer the forget / clear routes, so desktop pages get no controls.
        val editable = searchPrefix == null
        for ((heading, tiles) in listOf(t.recent to recent, t.quickLinks to links)) {
            if (tiles.isEmpty()) continue
            val isRecent = tiles === recent
            if (isRecent && editable) {
                append("<div class=\"h3-row\"><h3>").append(esc(heading)).append("</h3><a class=\"clear\" href=\"")
                    .append(CLEAR_RECENT).append("\">").append(esc(t.clear)).append("</a></div><div class=\"tiles\">")
            } else {
                append("<h3>").append(esc(heading)).append("</h3><div class=\"tiles\">")
            }
            tiles.forEach { link ->
                if (isRecent && editable) append("<div class=\"tile-wrap\">")
                append("<a class=\"tile\" href=\"").append(esc(link.url)).append("\"><span class=\"badge\" style=\"background:")
                    .append(cssColor(link.color)).append("\">").append(esc(link.badge)).append("</span><span class=\"t\">")
                    .append(esc(link.title)).append("</span><span class=\"h\">").append(esc(link.host)).append("</span></a>")
                if (isRecent && editable) {
                    append("<a class=\"forget\" href=\"").append(FORGET).append("?u=")
                        .append(esc(java.net.URLEncoder.encode(link.url, "UTF-8"))).append("\" aria-label=\"").append(esc(t.remove)).append("\">✕</a></div>")
                }
            }
            append("</div>")
        }
        if (searchPrefix != null) {
            // An address goes straight to its site, anything else to the engine, as the car does.
            append("<script>function go(f){var q=f.q.value.trim();if(q){location.href=")
            append("/^[a-z][a-z0-9+.-]*:\\/\\//i.test(q)?q:/^[^\\s\\/]+\\.[a-z]{2,}(\\/\\S*)?$/i.test(q)?'https://'+q:")
            append(org.json.JSONObject.quote(searchPrefix)).append("+encodeURIComponent(q)}return false}</script>")
        }
    })

    /** Asks before the Recent pages are wiped; the car WebView has no confirm() handler. */
    fun clearRecentHtml(t: Texts): String = page(t.clearRecentTitle, buildString {
        append("<header><h1>").append(esc(t.clearRecentTitle)).append("</h1></header>")
        append("<div class=\"actions center\"><a class=\"pill\" href=\"").append(CLEAR_RECENT).append("?confirm=1\">")
            .append(esc(t.clear)).append("</a><a class=\"pill ghost\" href=\"").append(URL).append("\">")
            .append(esc(t.cancel)).append("</a></div>")
    })

    /** The support channels' QR codes, for a passenger to scan from the head unit. */
    fun supportHtml(t: Texts, channels: List<Triple<String, String, String>>, backHref: String = URL): String = page(t.supportTitle, buildString {
        append("<a class=\"back\" href=\"").append(esc(backHref)).append("\">‹ ").append(esc(t.title)).append("</a>")
        append("<header><h1>").append(esc(t.supportTitle)).append("</h1><p class=\"sub\">").append(esc(t.supportMessage)).append("</p></header>")
        append("<div class=\"qrs\">")
        channels.forEach { (label, line, qr) ->
            append("<div class=\"qr\"><img alt=\"\" src=\"").append(qr).append("\"><strong>").append(esc(label))
                .append("</strong><span>").append(esc(line)).append("</span></div>")
        }
        append("</div>")
    })

    /** Sends a search to its page; replace() keeps this hop out of Back. */
    fun redirectHtml(target: String): String =
        "<!doctype html><meta charset=\"utf-8\"><script>location.replace(${org.json.JSONObject.quote(target)});</script>"

    private fun page(title: String, body: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>$CSS</style></head>
<body><main>$body</main></body></html>"""

    internal fun esc(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun cssColor(color: Int) = "#%06X".format(color and 0xFFFFFF)

    /** [color] at [alpha] (0..1), for tints of the theme's accent. */
    private fun cssColor(color: Int, alpha: Float) =
        "rgba(${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF},$alpha)"

    // The app's own palette (AutoBridgeDesign), so the page reads as part of AutoBridge.
    private val INK = cssColor(AutoBridgeDesign.INK)
    private val SURFACE = cssColor(AutoBridgeDesign.SURFACE)
    private val RAISED = cssColor(AutoBridgeDesign.SURFACE_RAISED)
    private val HAIRLINE = cssColor(AutoBridgeDesign.HAIRLINE)
    private val TEXT = cssColor(AutoBridgeDesign.TEXT)
    private val MUTED = cssColor(AutoBridgeDesign.TEXT_MUTED)
    private val ACCENT = cssColor(AutoBridgeDesign.ACCENT)

    private val SEARCH_ICON = """<svg class="lens" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="$ACCENT" stroke-width="2.6"/><path d="M15.5 15.5L21 21" stroke="$ACCENT" stroke-width="2.6" stroke-linecap="round"/></svg>"""
    private val ARROW_ICON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h13M12.5 6l6 6-6 6" fill="none" stroke="$INK" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
    private const val EXTERNAL_ICON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>"""

    private val CSS = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;min-height:100%}
body{background:radial-gradient(ellipse at 15% 0%,${cssColor(AutoBridgeDesign.ACCENT, .16f)} 0%,transparent 55%),
radial-gradient(ellipse at 90% 100%,${cssColor(AutoBridgeDesign.ACCENT_SOFT, .10f)} 0%,transparent 55%),$INK;background-attachment:fixed;color:$TEXT;
font-family:Roboto,"Noto Sans Thai","Noto Sans",sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:980px;margin:0 auto;padding:28px 28px 120px}
header{text-align:center;margin:8px 0 26px}
h1{font-size:44px;font-weight:800;letter-spacing:-.5px;margin:0 0 8px}
.sub{font-size:21px;color:$MUTED;margin:0;line-height:1.4}
.search{display:flex;align-items:center;gap:14px;background:$SURFACE;border:1px solid $HAIRLINE;border-radius:999px;height:76px;padding:0 10px 0 26px;
box-shadow:0 8px 28px rgba(0,0,0,.35);margin-bottom:26px}
.lens{width:32px;height:32px;flex:none}
.search input{flex:1;min-width:0;border:0;outline:0;background:transparent;font:inherit;font-size:24px;color:$TEXT;height:100%}
.search input::placeholder{color:$MUTED}
.search input::-webkit-search-cancel-button{display:none}
.search button{flex:none;width:58px;height:58px;border:0;border-radius:50%;background:$ACCENT;display:flex;align-items:center;justify-content:center;padding:0}
.search button svg{width:30px;height:30px}
.card{background:$SURFACE;border:1px solid $HAIRLINE;border-radius:30px;padding:24px 26px;margin-bottom:28px}
.card-head{display:flex;align-items:center;gap:18px;margin-bottom:12px}
.card-icon{flex:none;width:58px;height:58px;border-radius:18px;background:${cssColor(AutoBridgeDesign.ACCENT, .16f)};display:flex;align-items:center;justify-content:center;font-size:30px}
.card h2{font-size:25px;font-weight:700;margin:0}
.card p{font-size:19px;line-height:1.5;color:$MUTED;margin:0 0 18px}
.actions{display:flex;flex-wrap:wrap;gap:12px}
.pill{display:inline-flex;align-items:center;gap:12px;background:$ACCENT;color:$INK;text-decoration:none;font-weight:700;font-size:20px;
height:58px;padding:0 26px;border-radius:999px}
.pill svg{width:24px;height:24px}
.pill.ghost{background:$RAISED;color:$TEXT;border:1px solid $HAIRLINE}
h3{font-size:16px;font-weight:600;letter-spacing:2px;color:$MUTED;margin:0 4px 14px}
.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:16px}
.tiles+h3,.tiles+.h3-row{margin-top:26px}
.h3-row{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}
.h3-row h3{margin:0 4px}
.clear{color:$ACCENT;text-decoration:none;font-weight:700;font-size:18px;min-height:44px;display:inline-flex;align-items:center;padding:0 14px}
.tile-wrap{position:relative;min-width:0;display:flex}
.tile-wrap .tile{flex:1}
.forget{position:absolute;top:4px;right:4px;width:44px;height:44px;display:flex;align-items:center;justify-content:center;color:$MUTED;text-decoration:none;font-size:20px}
.actions.center{justify-content:center}
.tile{display:flex;flex-direction:column;align-items:center;text-align:center;gap:6px;background:$SURFACE;border:1px solid $HAIRLINE;
border-radius:24px;padding:18px 10px 16px;text-decoration:none;color:inherit;min-width:0}
.badge{width:64px;height:64px;border-radius:18px;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:800;font-size:24px;margin-bottom:6px}
.tile .t,.tile .h{max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.tile .t{font-size:18px;font-weight:600}
.tile .h{font-size:14px;color:$MUTED}
.back{display:inline-block;color:$ACCENT;text-decoration:none;font-weight:700;font-size:20px;padding:8px 0}
.qrs{display:flex;flex-wrap:wrap;gap:18px;justify-content:center}
.qr{display:flex;flex-direction:column;align-items:center;gap:8px;background:$SURFACE;border:1px solid $HAIRLINE;border-radius:26px;padding:18px;width:260px;text-align:center}
.qr img{width:220px;height:220px;border-radius:12px;background:#fff}
.qr strong{font-size:20px}
.qr span{font-size:15px;color:$MUTED;word-break:break-all}
"""
}
