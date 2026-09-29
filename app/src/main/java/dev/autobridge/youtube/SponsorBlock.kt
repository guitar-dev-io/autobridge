package dev.autobridge.youtube

import org.json.JSONArray
import java.security.MessageDigest

/**
 * The crowd-sourced segment categories the SponsorBlock database serves.
 *
 * [apiId] is the identifier the API speaks; [onByDefault] is what a user who turns the feature on
 * without opening the category list gets. Only the categories that are uncontroversially "not the
 * video you asked for" start enabled — skipping the intro or the outro of every video by default
 * would silently cut content people came to watch.
 */
enum class SponsorCategory(
    val apiId: String,
    val label: String,
    val caption: String,
    val onByDefault: Boolean
) {
    SPONSOR("sponsor", "Sponsor", "Paid promotion inside the video", true),
    SELF_PROMO("selfpromo", "Self-promotion", "Merch, Patreon, the creator's other channels", true),
    INTERACTION("interaction", "Interaction reminder", "\"Like and subscribe\"", true),
    INTRO("intro", "Intro", "Opening animation or title card", false),
    OUTRO("outro", "Outro", "End cards and credits", false),
    PREVIEW("preview", "Preview / recap", "A summary of what is coming or what happened", false),
    MUSIC_OFFTOPIC("music_offtopic", "Non-music section", "Talking in a music video", false),
    FILLER("filler", "Filler", "Tangents and jokes that add no information", false);

    companion object {
        fun byApiId(id: String): SponsorCategory? = entries.firstOrNull { it.apiId == id }
    }
}

/** One stretch of a video the database says can be skipped. Times are seconds. */
data class SponsorSegment(val category: SponsorCategory, val start: Double, val end: Double) {
    val length: Double get() = end - start
}

/**
 * Parsing and skip arithmetic for SponsorBlock, kept free of Android and of the network so the
 * behaviour that matters — which segment applies at a given moment, and what the page is told to
 * do about it — is asserted in a JVM test instead of on a head unit.
 */
object SponsorBlock {
    /**
     * A segment shorter than this is not worth a jump: the seek itself costs more than the
     * segment, and on a live-ish HLS buffer it can land the player back where it started.
     */
    const val MIN_SEGMENT_SECONDS = 1.0

    /** How close to the end of a segment still counts as inside it. */
    private const val EDGE_TOLERANCE = 0.25

    /**
     * The API is queried by the first four characters of the SHA-256 of the video id, so the
     * server is told a bucket of roughly 1 in 65,536 videos rather than which video is playing.
     * The exact match is then made locally in [parse].
     */
    fun hashPrefix(videoId: String, length: Int = 4): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }

    /**
     * Reads the `skipSegments` response, keeping only [enabled] categories of the one video asked
     * for. The response covers every video sharing the hash prefix, so filtering by [videoId] is
     * required for correctness, not just for tidiness.
     */
    fun parse(body: String, videoId: String, enabled: Set<SponsorCategory>): List<SponsorSegment> {
        if (enabled.isEmpty()) return emptyList()
        val videos = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val segments = mutableListOf<SponsorSegment>()
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            if (video.optString("videoID") != videoId) continue
            val list = video.optJSONArray("segments") ?: continue
            for (position in 0 until list.length()) {
                val item = list.optJSONObject(position) ?: continue
                // "skip" is the only action this app performs. "mute" and "full" would need a
                // different response from the player, and silently skipping them would remove
                // more of the video than the category name promises.
                if (item.optString("actionType", "skip") != "skip") continue
                val category = SponsorCategory.byApiId(item.optString("category")) ?: continue
                if (category !in enabled) continue
                val bounds = item.optJSONArray("segment") ?: continue
                if (bounds.length() < 2) continue
                val start = bounds.optDouble(0, Double.NaN)
                val end = bounds.optDouble(1, Double.NaN)
                if (start.isNaN() || end.isNaN() || end - start < MIN_SEGMENT_SECONDS) continue
                segments += SponsorSegment(category, start, end)
            }
        }
        return merge(segments)
    }

    /**
     * Sorts by start time and folds overlapping segments together, so a position can never match
     * two entries and a skip can never land inside the next one.
     */
    fun merge(segments: List<SponsorSegment>): List<SponsorSegment> {
        val sorted = segments.sortedBy { it.start }
        val merged = mutableListOf<SponsorSegment>()
        sorted.forEach { segment ->
            val last = merged.lastOrNull()
            if (last != null && segment.start <= last.end) {
                if (segment.end > last.end) merged[merged.size - 1] = last.copy(end = segment.end)
            } else {
                merged += segment
            }
        }
        return merged
    }

    /**
     * Where playback should jump to from [position], or null to keep playing. Mirrors exactly what
     * the injected script does, so the rule is testable here rather than only in a page.
     */
    fun skipTarget(segments: List<SponsorSegment>, position: Double): Double? =
        segments.firstOrNull { position >= it.start && position < it.end - EDGE_TOLERANCE }?.end

    /**
     * The script that performs the skipping inside the page.
     *
     * It is a fixed string built from numbers this app fetched; nothing from the page is read back
     * and no `@JavascriptInterface` object is exposed, for the same reason
     * [dev.autobridge.audio.WebAudioBridge] exposes none: the browser loads arbitrary sites and
     * none of them should get a handle on the app.
     *
     * The listener is attached once per `<video>` element and afterwards only the segment list is
     * replaced, because YouTube reuses the same element across its in-page navigations.
     */
    fun script(videoId: String, segments: List<SponsorSegment>): String {
        val list = segments.joinToString(",") { "[${it.start},${it.end}]" }
        return """
            (function(){
              var v = document.querySelector('video');
              if (!v) return 'no-video';
              window.__abSponsorSegments = [$list];
              window.__abSponsorVideo = '$videoId';
              if (v.dataset.abSponsor === '1') return 'updated';
              v.dataset.abSponsor = '1';
              v.addEventListener('timeupdate', function(){
                var list = window.__abSponsorSegments || [];
                var t = v.currentTime;
                for (var i = 0; i < list.length; i++) {
                  if (t >= list[i][0] && t < list[i][1] - $EDGE_TOLERANCE) {
                    v.currentTime = list[i][1];
                    return;
                  }
                }
              });
              return 'armed';
            })();
        """.trimIndent()
    }

    /** Clears any armed segment list, for when the feature is switched off mid-page. */
    fun clearScript(): String =
        "(function(){ window.__abSponsorSegments = []; return 'cleared'; })();"

    /**
     * Asks the page's own player for its best quality.
     *
     * `getAvailableQualityLevels()` is ordered highest first, so the first entry is the target.
     * Both setters are called because the mobile and desktop players have disagreed about which
     * one sticks; the range form is what survives an adaptive downgrade.
     */
    fun highestQualityScript(): String =
        """
        (function(){
          var p = document.getElementById('movie_player') ||
                  document.querySelector('.html5-video-player');
          if (!p || !p.getAvailableQualityLevels) return 'no-player';
          var levels = p.getAvailableQualityLevels();
          if (!levels || !levels.length) return 'no-levels';
          var best = levels[0];
          try {
            if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(best, best);
            if (p.setPlaybackQuality) p.setPlaybackQuality(best);
          } catch (e) { return 'refused'; }
          return best;
        })();
        """.trimIndent()
}
