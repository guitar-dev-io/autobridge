package dev.autobridge.iptv

/**
 * Xtream Codes account coordinates: a portal base URL plus username/password.
 *
 * Providers hand these out in three shapes, so [parse] accepts all of them:
 *  - a bare portal ("http://host:8080") with the credentials entered separately;
 *  - a full `player_api.php?username=U&password=P` link;
 *  - a full `get.php?username=U&password=P&type=m3u_plus` playlist link.
 *
 * Everything here is pure string work so the URL shapes are unit-testable without a device or a
 * live portal. Network access lives in [XtreamClient].
 */
data class XtreamCredentials(
    val portal: String,
    val username: String,
    val password: String
) {
    /** `http://host:port/player_api.php?username=…&password=…&action=…` */
    fun apiUrl(action: String? = null, params: Map<String, String> = emptyMap()): String {
        val query = StringBuilder("username=").append(encode(username))
            .append("&password=").append(encode(password))
        if (!action.isNullOrBlank()) query.append("&action=").append(encode(action))
        params.forEach { (key, value) ->
            query.append('&').append(encode(key)).append('=').append(encode(value))
        }
        return "$portal/player_api.php?$query"
    }

    /** The provider's full m3u_plus playlist, used as a fallback when `player_api.php` is absent. */
    fun playlistUrl(): String =
        "$portal/get.php?username=${encode(username)}&password=${encode(password)}&type=m3u_plus&output=ts"

    /** Direct live stream URL. HLS (`m3u8`) is preferred; `ts` is the legacy fallback. */
    fun liveUrl(streamId: String, extension: String = "m3u8"): String =
        "$portal/live/${encode(username)}/${encode(password)}/$streamId.$extension"

    /** Direct VOD (movie) URL. [extension] comes from the portal's `container_extension`. */
    fun vodUrl(streamId: String, extension: String): String =
        "$portal/movie/${encode(username)}/${encode(password)}/$streamId.${extension.ifBlank { "mp4" }}"

    /** Direct series-episode URL. [extension] comes from the episode's `container_extension`. */
    fun seriesUrl(episodeId: String, extension: String): String =
        "$portal/series/${encode(username)}/${encode(password)}/$episodeId.${extension.ifBlank { "mp4" }}"

    /**
     * Catch-up (timeshift) URL for a live channel, when the provider exposes archive data.
     * [start] is `yyyy-MM-dd:HH-mm` in the portal's timezone and [durationMinutes] the length.
     */
    fun catchupUrl(streamId: String, start: String, durationMinutes: Int): String =
        "$portal/streaming/timeshift.php?username=${encode(username)}&password=${encode(password)}" +
            "&stream=$streamId&start=${encode(start)}&duration=$durationMinutes"

    companion object {
        /**
         * Builds credentials from free-form user input.
         *
         * [input] may be a portal URL or a full `player_api.php`/`get.php` link. Credentials found
         * in the link win over [username]/[password] only when those are blank, so a user who
         * pastes a link and also types a username still gets what they typed. Returns null when no
         * usable portal or credential pair can be formed.
         */
        fun parse(input: String?, username: String? = null, password: String? = null): XtreamCredentials? {
            val raw = input?.trim().orEmpty()
            if (raw.isEmpty()) return null

            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val schemeEnd = withScheme.indexOf("://") + 3
            val scheme = withScheme.substring(0, schemeEnd).lowercase()
            if (scheme != "http://" && scheme != "https://") return null

            val rest = withScheme.substring(schemeEnd)
            val authorityAndPath = rest.substringBefore('?').substringBefore('#')
            val authority = authorityAndPath.substringBefore('/')
            if (authority.isBlank()) return null

            val query = rest.substringAfter('?', "").substringBefore('#')
            val parameters = parseQuery(query)

            // Keep any directory prefix ("/iptv/") but drop the endpoint file itself.
            val path = authorityAndPath.removePrefix(authority)
            val directory = path.substringBeforeLast('/', "")
                .trimEnd('/')
                .takeIf { it.isNotBlank() && !it.endsWith(".php") }
                .orEmpty()

            val portal = (scheme + authority + directory).trimEnd('/')
            val user = username?.trim()?.takeIf { it.isNotEmpty() }
                ?: parameters["username"]?.takeIf { it.isNotEmpty() }
                ?: return null
            val secret = password?.trim()?.takeIf { it.isNotEmpty() }
                ?: parameters["password"]?.takeIf { it.isNotEmpty() }
                ?: return null

            return XtreamCredentials(portal, user, secret)
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
            }.toMap()

        private fun encode(value: String): String = buildString {
            value.forEach { character ->
                when {
                    character.isLetterOrDigit() || character in "-._~" -> append(character)
                    else -> character.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                        append('%').append("%02X".format(byte.toInt() and 0xFF))
                    }
                }
            }
        }

        private fun decode(value: String): String {
            if ('%' !in value && '+' !in value) return value
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character == '+' -> { bytes.write(' '.code); index++ }
                    character == '%' && index + 2 < value.length -> {
                        val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                        if (hex == null) { bytes.write(character.code); index++ }
                        else { bytes.write(hex); index += 3 }
                    }
                    else -> { bytes.write(character.toString().toByteArray(Charsets.UTF_8)); index++ }
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
