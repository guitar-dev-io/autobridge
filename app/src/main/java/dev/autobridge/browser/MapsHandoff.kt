package dev.autobridge.browser

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Hands a destination from the split's Google Maps page to the real Google Maps app.
 *
 * The side page is Google Maps for the mobile web, and the mobile web has no turn-by-turn
 * navigation: its "Start" / "Open app" buttons only try to launch the Maps app through an
 * `intent://` or `google.navigation:` link. The browser drops every non-web link, so on the car
 * those buttons did nothing and the map could show a route but never drive it. This reads the
 * destination out of such a link — or out of the page's own URL, for the menu's "navigate" action —
 * so the surface can start real navigation in the Maps app, which Android Auto then shows.
 *
 * Pure string work (no android.net.Uri), so the parsing is covered by a plain JVM test.
 */
object MapsHandoff {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** The intent URI that starts Google Maps navigation to [destination]. */
    fun navigationUri(destination: String): String =
        "google.navigation:q=" + URLEncoder.encode(destination, "UTF-8")

    /**
     * What to navigate to when the page in [pageUrl] asks to leave for [link]: the destination in
     * the link itself, or — when the link only says "open the Maps app", as the mobile site's
     * "Open Google Maps app? → Continue" prompt does — the place or route the page is showing.
     * Null when the link is not aimed at the Maps app, or neither says where to go.
     */
    fun handoffDestination(link: String, pageUrl: String?): String? =
        destinationFromLink(link) ?: if (isMapsAppLink(link)) destinationFromPage(pageUrl) else null

    /** True when [link] asks for the Google Maps app rather than for a web page. */
    fun isMapsAppLink(link: String): Boolean {
        val lower = link.lowercase()
        return lower.startsWith("google.navigation:") || lower.startsWith("geo:") ||
            lower.startsWith("comgooglemaps:") || lower.startsWith("google.maps:") ||
            (lower.startsWith("intent:") && lower.contains("package=$MAPS_PACKAGE"))
    }

    /**
     * The destination a non-web link from the Maps page was asking the app to navigate to, or
     * null when the link is not a Maps navigation hand-off.
     */
    fun destinationFromLink(link: String): String? {
        val lower = link.lowercase()
        return when {
            lower.startsWith("google.navigation:") ->
                queryParam(link.substringAfter(':'), "q")
            lower.startsWith("geo:") ->
                queryParam(link.substringAfter('?', ""), "q")
                    ?: link.substringAfter(':').substringBefore('?').takeIf { it.isNotBlank() && it != "0,0" }
            lower.startsWith("intent:") -> fromIntentLink(link)
            else -> null
        }
    }

    /**
     * The destination a Google Maps web URL is showing — a route's end, a place, or a search —
     * or null when the page is not one (the map's start screen, another site).
     */
    fun destinationFromPage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        // google.com, google.co.th, maps.google.com…: every country domain serves the same map.
        if (!GOOGLE_HOST.matches(host)) return null
        val query = uri.rawQuery.orEmpty()
        queryParam(query, "destination")?.let { return it }
        queryParam(query, "daddr")?.let { return it }
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val maps = segments.indexOf("maps")
        val rest = if (maps >= 0) segments.drop(maps + 1) else segments
        val kind = rest.firstOrNull()
        val args = rest.drop(1)
            .takeWhile { !it.startsWith("@") && !it.startsWith("data=") && !it.startsWith("am=") }
            .map(::decode)
            .filter { it.isNotBlank() }
        return when (kind) {
            // /maps/dir/<origin>/<destination>/@...: the last stop is where the route ends.
            "dir" -> args.lastOrNull()
            "place", "search" -> args.firstOrNull()
            else -> queryParam(query, "q")
        }
    }

    private fun fromIntentLink(link: String): String? {
        // intent://<rest>#Intent;scheme=<s>;package=<p>;...;end
        val fragment = link.substringAfter("#Intent;", "")
        val extras = fragment.split(';').mapNotNull {
            val key = it.substringBefore('=', "")
            if (key.isEmpty()) null else key to it.substringAfter('=')
        }.toMap()
        val target = extras["package"]
        if (target != null && target != MAPS_PACKAGE) return null
        val body = link.substringAfter("intent:").removePrefix("//").substringBefore("#Intent;")
        // The web page the link falls back to when the app is missing names the same place.
        val fallback = extras["S.browser_fallback_url"]?.let(::decode)?.let(::destinationFromPage)
        return fallback ?: when (extras["scheme"]?.lowercase()) {
            "google.navigation" -> queryParam(body, "q")
            "geo" -> destinationFromLink("geo:$body")
            else -> {
                // The app's own links are often host-less ("intent://maps/dir/..."): read them as
                // Google Maps web URLs.
                val withHost = if (body.substringBefore('/').contains('.')) body else "www.google.com/$body"
                destinationFromPage("https://$withHost")
            }
        }
    }

    private val GOOGLE_HOST = Regex("(^|.*\\.)google\\.[a-z]{2,3}(\\.[a-z]{2})?$")

    private fun queryParam(query: String, name: String): String? =
        query.substringAfter('?').split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') == name) decode(pair.substringAfter('=', "")) else null
        }?.takeIf { it.isNotBlank() }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value).trim()
}
