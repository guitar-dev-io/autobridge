# YouTube add-ons

Optional behaviour applied to YouTube pages inside AutoBridge's own browser — the phone
`BrowserActivity` and the car `CarWebRenderer`, which share the implementation so the car does not
behave differently from the hand.

Both features are **off until switched on** in Settings → YouTube add-ons. One changes what is
played, the other overrides a decision the site made about the connection, and SponsorBlock sends a
request to a third party; none of that may happen because the app was installed.

## SponsorBlock

Skips crowd-sourced segments (sponsor reads, self-promotion, "like and subscribe" reminders, and on
request intros, outros, recaps, non-music sections and filler).

| Piece | File | What it does |
|---|---|---|
| Categories, parsing, skip arithmetic, injected script | `SponsorBlock` | Pure Kotlin: no Android, no network — unit tested |
| URL handling | `YouTubeUrls` | watch / youtu.be / shorts / embed / live, with playlist and `t=` parameters |
| Lookup + cache | `SponsorBlockClient` | Blocking HTTP off the main thread, cached per video and category set |
| Settings | `YouTubeSettings` | Master switch plus a per-category switch |
| Glue | `YouTubeEnhancer` | One per WebView; driven by the WebViewClient |

### Privacy

The lookup asks for a **four-character SHA-256 prefix of the video id**, which is the endpoint the
API documents for this purpose. The server is told a bucket of roughly one video in 65,536 rather
than which video is playing; the exact match happens on the device, in `SponsorBlock.parse`. No
account, device or user identifier is sent, and nothing is sent at all while the feature is off.

### Why the skipping runs in the page

The segment list is injected as a fixed script that attaches one `timeupdate` listener to the
`<video>` element. No `@JavascriptInterface` object is exposed, for the same reason
`WebAudioBridge` exposes none: the browser loads arbitrary sites and none of them should get a
handle on the app. Data flows one way — this app's own numbers go in, nothing comes back out.

### Why URL changes drive it, not page loads

YouTube is a single-page app: the next video rewrites the address with `pushState` and never fires
`onPageFinished`. Hooking page loads alone handled the first video of a session and nothing after
it. `doUpdateVisitedHistory` does fire for those in-page navigations, so both callbacks feed
`YouTubeEnhancer.onPageChanged`, which ignores a repeat of the id it already armed.

### Deliberate limits

- Only `actionType: "skip"` is honoured. "mute" and "full" need a different response from the
  player, and skipping them would remove more than the category name promises.
- Segments shorter than one second are dropped: the seek costs more than the segment.
- Overlapping segments are merged, so a skip can never land inside the next one.
- A failed lookup is remembered as "nothing to skip" for the rest of the process. Retrying on every
  `timeupdate` would hammer a public service.

## Auto highest quality

Asks the page's own player for `getAvailableQualityLevels()` (ordered highest first) and pins it
with `setPlaybackQualityRange`. It runs shortly after the player element appears; if the player is
not there yet the script reports `no-player` and the next navigation tries again.

This is a request, not a guarantee: YouTube may still adapt downwards on a weak connection, and a
head unit on mobile data will pay for whatever it does honour.

## Verified

On a Xiaomi 13T Pro (Android 16), personal debug build:

- `auto quality -> "hd2160"` — the highest level the player offered was applied.
- The enhancer fires on the first load *and* on in-page navigation between videos.
- A lookup for a video with no submitted segments reports `SponsorBlock: no segments` and injects
  nothing.
- 10 JVM tests cover the URL shapes, the hash prefix, response parsing (wrong video, disabled
  category, unknown category, muted segment, sub-second segment), merging, the skip target at the
  edges, and that the injected script carries no native handle.

Not yet observed on the device: an armed skip firing on a video that *does* have segments. The
lookup and injection paths around it are verified; what is untested is the last hop, the page
acting on the list.
