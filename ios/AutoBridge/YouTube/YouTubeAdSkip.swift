import Foundation

/// The in-page half of ad blocking on YouTube: skipping the ads that cannot be blocked by host.
///
/// A content-blocker rule list drops the requests that are only ever advertising, which covers
/// banners, the scripts that place them and their measurement beacons. It cannot touch a pre-roll or
/// a mid-roll, because YouTube serves ad video from `googlevideo.com` — the same host as the video
/// the user asked for — and describes the break inside the `/youtubei/v1/player` reply that also
/// carries the stream URLs. Drop either and there is no playback at all.
///
/// So the break is ended from inside the page instead, the way the browser extensions do it: press
/// the Skip button when YouTube offers one, and otherwise seek the ad to its end, which is the one
/// operation the player accepts on an unskippable ad. The ad is still fetched and may flash up for a
/// frame or two before the seek lands; this does not pretend otherwise.
///
/// ## Why a poll and not a MutationObserver
///
/// The signal is a class on the player element (`ad-showing`), not a node being inserted, and an
/// observer wide enough to catch an attribute change anywhere under the player fires on every frame
/// of ordinary playback — the progress bar alone rewrites attributes continuously. A 300 ms poll
/// that reads two selectors is the cheaper of the two, and a few hundred milliseconds of latency on
/// a skip is not noticeable next to the seek itself.
///
/// ## Why this is fragile, stated plainly
///
/// Every selector here is YouTube's own internal class name. They change without notice, and when
/// they do a skip quietly stops happening until they are updated. YouTube also detects ad blocking
/// and may show its "ad blockers are not allowed" interstitial; nothing here tries to defeat that
/// check. This is why the feature is opt-in rather than something the browser does on its own.
/// Mirrors the Android `YouTubeAdSkip`.
public enum YouTubeAdSkip {
    /// How often `script` looks at the player.
    private static let pollMs = 300

    /// Close enough to the end of an ad that another seek would be wasted. Without it the poll
    /// re-seeks on every tick for as long as the player takes to advance to the content.
    private static let endTolerance = 0.15

    /// The Skip button, across the player versions that have shipped under each name.
    private static let skipSelectors =
        ".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, "
        + ".ytp-ad-skip-button-slot button"

    /// Feed and watch-page ad slots, hidden rather than dropped.
    ///
    /// These arrive as part of the same `/youtubei/v1/browse` reply as the videos next to them, so
    /// there is no request to block: the only place the two can be told apart is the DOM, after
    /// YouTube has rendered each into its own element type.
    private static let cosmeticSelectors =
        "#player-ads, #masthead-ad, ytd-ad-slot-renderer, ytd-display-ad-renderer, "
        + "ytd-promoted-sparkles-web-renderer, ytd-promoted-video-renderer, "
        + "ytd-in-feed-ad-layout-renderer, ytm-promoted-video-renderer, "
        + ".ytp-ad-overlay-slot, ytd-banner-promo-renderer"

    /// Installs the skipper, or re-enables it if a previous call already did.
    ///
    /// Safe to evaluate on every navigation: YouTube reuses one document across its in-page
    /// navigations, so the second call finds its own state object and only flips the flag back on
    /// rather than starting a second timer.
    public static func script() -> String {
        """
        (function(){
          var s = window.__abAdSkip;
          if (s) {
            s.on = true;
            // A clearScript() in between stopped the timer; the state object outlives it.
            if (!s.timer && s.tick) s.timer = setInterval(s.tick, \(pollMs));
            return 'rearmed';
          }
          s = { on: true };
          window.__abAdSkip = s;

          var style = document.createElement('style');
          style.textContent = '\(cosmeticSelectors) { display: none !important; }';
          (document.head || document.documentElement).appendChild(style);

          function player() {
            return document.getElementById('movie_player') ||
                   document.querySelector('.html5-video-player');
          }

          function tick() {
            if (!s.on) return;
            var p = player();
            if (!p) return;

            // The dismissable banner over the bottom of the video is its own thing and shows
            // without the player entering an ad break.
            var overlay = document.querySelector('.ytp-ad-overlay-close-button');
            if (overlay) overlay.click();

            if (!p.classList.contains('ad-showing') &&
                !p.classList.contains('ad-interrupting')) return;

            var skip = document.querySelector('\(skipSelectors)');
            if (skip) { skip.click(); return; }

            var v = p.querySelector('video') || document.querySelector('video');
            if (!v) return;
            var end = v.duration;
            // A live or not-yet-loaded ad reports Infinity or 0; seeking there would throw.
            if (!isFinite(end) || end <= 0) return;
            if (end - v.currentTime > \(endTolerance)) v.currentTime = end;
            if (v.paused) { var r = v.play(); if (r && r.catch) r.catch(function(){}); }
          }

          s.tick = tick;
          s.timer = setInterval(tick, \(pollMs));
          return 'armed';
        })();
        """
    }

    /// Stops the skipper, for when the setting is switched off while a page is open.
    ///
    /// The timer is cleared and the state object is kept, so a later `script` call re-arms the same
    /// instance instead of leaving a dead object behind and installing a second stylesheet.
    public static func clearScript() -> String {
        """
        (function(){
          var s = window.__abAdSkip;
          if (!s) return 'absent';
          s.on = false;
          if (s.timer) { clearInterval(s.timer); s.timer = null; }
          return 'stopped';
        })();
        """
    }
}
