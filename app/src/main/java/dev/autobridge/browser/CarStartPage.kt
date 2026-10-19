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
import dev.autobridge.support.QrCode
import dev.autobridge.support.SupportChannels
import java.io.ByteArrayOutputStream

/**
 * The car browser's start page, drawn by the browser itself the way AABrowser draws its own: a
 * soft gradient, the title, one large search field, the support card and the quick links.
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

    private const val TEAL = "#0B6E80"
    private const val QUICK_LINK_LIMIT = 12
    private const val QR_PX = 360

    fun isStartPage(url: String?): Boolean = url != null && url.substringBefore('?').substringBefore('#') == URL

    /** True for the page's own addresses, which must never be saved as a site or resumed as one. */
    fun isInternal(url: String?): Boolean = url != null && url.startsWith("$BASE/")

    /** The "desktop browser" link: the renderer hands it to the car screen instead of loading it. */
    fun isDesktopLink(url: String): Boolean = url.substringBefore('?') == DESKTOP

    /** A tile on the page: one saved site. */
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
            URL -> html(startHtml(texts(context), quickLinks(context), desktopAvailable))
            GO -> html(redirectHtml(target(context, url)))
            SUPPORT -> html(supportHtml(texts(context), supportChannels(context)))
            else -> if (isInternal(path)) html(redirectHtml(URL)) else null
        }
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

    /** The page itself. Pure, so the markup is testable without a WebView. */
    fun startHtml(t: Texts, links: List<QuickLink>, desktopAvailable: Boolean): String = page(t.title, buildString {
        append("<header><h1>").append(esc(t.title)).append("</h1><p class=\"sub\">").append(esc(t.subtitle)).append("</p></header>")
        append("<form class=\"search\" action=\"").append(GO).append("\" method=\"get\" autocomplete=\"off\">")
        append(SEARCH_ICON)
        append("<input name=\"q\" type=\"search\" enterkeyhint=\"go\" placeholder=\"").append(esc(t.searchHint)).append("\" aria-label=\"").append(esc(t.searchHint)).append("\">")
        append("<button type=\"submit\" aria-label=\"").append(esc(t.go)).append("\">").append(ARROW_ICON).append("</button></form>")

        append("<section class=\"card\"><div class=\"card-head\"><div class=\"card-icon\">☕</div><h2>")
            .append(esc(t.supportTitle)).append("</h2></div><p>").append(esc(t.supportMessage)).append("</p>")
        append("<div class=\"actions\"><a class=\"pill\" href=\"").append(SUPPORT).append("\">").append(esc(t.supportAction)).append(EXTERNAL_ICON).append("</a>")
        if (desktopAvailable) {
            append("<a class=\"pill ghost\" href=\"").append(DESKTOP).append("\">").append(esc(t.desktop)).append("</a>")
        }
        append("</div></section>")

        if (links.isNotEmpty()) {
            append("<h3>").append(esc(t.quickLinks)).append("</h3><div class=\"tiles\">")
            links.forEach { link ->
                append("<a class=\"tile\" href=\"").append(esc(link.url)).append("\"><span class=\"badge\" style=\"background:")
                    .append(cssColor(link.color)).append("\">").append(esc(link.badge)).append("</span><span class=\"t\">")
                    .append(esc(link.title)).append("</span><span class=\"h\">").append(esc(link.host)).append("</span></a>")
            }
            append("</div>")
        }
    })

    /** The support channels' QR codes, for a passenger to scan from the head unit. */
    fun supportHtml(t: Texts, channels: List<Triple<String, String, String>>): String = page(t.supportTitle, buildString {
        append("<a class=\"back\" href=\"").append(URL).append("\">‹ ").append(esc(t.title)).append("</a>")
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

    private const val SEARCH_ICON = """<svg class="lens" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="$TEAL" stroke-width="2.6"/><path d="M15.5 15.5L21 21" stroke="$TEAL" stroke-width="2.6" stroke-linecap="round"/></svg>"""
    private const val ARROW_ICON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h13M12.5 6l6 6-6 6" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
    private const val EXTERNAL_ICON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>"""

    private const val CSS = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;min-height:100%}
body{background:linear-gradient(135deg,#CDE6FB 0%,#DCE2FB 45%,#C6EEFA 100%) fixed;color:#17202B;
font-family:Roboto,"Noto Sans Thai","Noto Sans",sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:980px;margin:0 auto;padding:28px 28px 120px}
header{text-align:center;margin:8px 0 26px}
h1{font-size:44px;font-weight:800;letter-spacing:-.5px;margin:0 0 8px}
.sub{font-size:21px;color:#4A5563;margin:0;line-height:1.4}
.search{display:flex;align-items:center;gap:14px;background:#fff;border-radius:999px;height:76px;padding:0 10px 0 26px;
box-shadow:0 6px 24px rgba(30,64,110,.10);margin-bottom:26px}
.lens{width:32px;height:32px;flex:none}
.search input{flex:1;min-width:0;border:0;outline:0;background:transparent;font:inherit;font-size:24px;color:#17202B;height:100%}
.search input::placeholder{color:#8A94A3}
.search input::-webkit-search-cancel-button{display:none}
.search button{flex:none;width:58px;height:58px;border:0;border-radius:50%;background:$TEAL;display:flex;align-items:center;justify-content:center;padding:0}
.search button svg{width:30px;height:30px}
.card{background:rgba(255,255,255,.92);border-radius:30px;padding:24px 26px;box-shadow:0 6px 24px rgba(30,64,110,.08);margin-bottom:28px}
.card-head{display:flex;align-items:center;gap:18px;margin-bottom:12px}
.card-icon{flex:none;width:58px;height:58px;border-radius:18px;background:#D4EFFA;display:flex;align-items:center;justify-content:center;font-size:30px}
.card h2{font-size:25px;font-weight:700;margin:0}
.card p{font-size:19px;line-height:1.5;color:#4A5563;margin:0 0 18px}
.actions{display:flex;flex-wrap:wrap;gap:12px}
.pill{display:inline-flex;align-items:center;gap:12px;background:$TEAL;color:#fff;text-decoration:none;font-weight:700;font-size:20px;
height:58px;padding:0 26px;border-radius:999px}
.pill svg{width:24px;height:24px}
.pill.ghost{background:#E3F1F6;color:$TEAL}
h3{font-size:16px;font-weight:700;letter-spacing:.6px;text-transform:uppercase;color:#5B6676;margin:0 4px 14px}
.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:16px}
.tile{display:flex;flex-direction:column;align-items:center;text-align:center;gap:6px;background:rgba(255,255,255,.72);
border-radius:24px;padding:18px 10px 16px;text-decoration:none;color:inherit;min-width:0}
.badge{width:64px;height:64px;border-radius:18px;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:800;font-size:24px;margin-bottom:6px}
.tile .t,.tile .h{max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.tile .t{font-size:18px;font-weight:600}
.tile .h{font-size:14px;color:#6B7584}
.back{display:inline-block;color:$TEAL;text-decoration:none;font-weight:700;font-size:20px;padding:8px 0}
.qrs{display:flex;flex-wrap:wrap;gap:18px;justify-content:center}
.qr{display:flex;flex-direction:column;align-items:center;gap:8px;background:#fff;border-radius:26px;padding:18px;width:260px;text-align:center}
.qr img{width:220px;height:220px;border-radius:12px}
.qr strong{font-size:20px}
.qr span{font-size:15px;color:#5B6676;word-break:break-all}
"""
}
