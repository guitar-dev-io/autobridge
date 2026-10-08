import Foundation
import WebKit

/// Applies the YouTube add-ons to whatever a `WKWebView` is currently showing.
///
/// One instance per web view. `BrowserView`'s model calls `onPageChanged` both when a load finishes
/// and when the address changes, so the behaviour matches the Android browser.
///
/// ## Why this is driven by URL changes and not by page loads alone
///
/// YouTube is a single-page app: moving from one video to the next rewrites the address with
/// `pushState` and never finishes a navigation. Hooking only page loads meant the first video of a
/// session was handled and every video after it was not. KVO on `WKWebView.url` does fire for those
/// in-page navigations — it is the iOS counterpart of Android's `doUpdateVisitedHistory` — and this
/// class ignores a repeat of the id it already armed, so both can be wired to it without doing the
/// work twice. Mirrors the Android `YouTubeEnhancer`.
@MainActor
public final class YouTubeEnhancer {
    /// How long the player needs to exist before the quality script can find it.
    private static let playerSettle: Duration = .milliseconds(1_200)

    private let settings: YouTubeSettings
    private var armedVideoId: String?
    private var qualityAppliedTo: String?
    private var adSkipArmed = false
    private var lookup: Task<Void, Never>?
    private var qualityTask: Task<Void, Never>?

    public init(settings: YouTubeSettings) {
        self.settings = settings
    }

    public func onPageChanged(_ webView: WKWebView, url: String) {
        applyAdSkip(webView, url: url)

        guard let videoId = YouTubeUrls.videoId(url) else {
            armedVideoId = nil
            qualityAppliedTo = nil
            return
        }

        if settings.autoHighestQuality && qualityAppliedTo != videoId {
            qualityAppliedTo = videoId
            // The player is built after the document settles; a single delayed attempt is enough in
            // practice and costs nothing when it is early — the script reports 'no-player' and the
            // next navigation tries again.
            qualityTask?.cancel()
            qualityTask = Task { [weak self, weak webView] in
                try? await Task.sleep(for: Self.playerSettle)
                guard !Task.isCancelled, let webView, self != nil else { return }
                await Self.evaluate(webView, SponsorBlock.highestQualityScript())
            }
        }

        let categories = settings.enabledCategories
        if categories.isEmpty {
            if armedVideoId != nil {
                armedVideoId = nil
                Task { await Self.evaluate(webView, SponsorBlock.clearScript()) }
            }
            return
        }
        if armedVideoId == videoId { return }
        armedVideoId = videoId

        if let cached = SponsorBlockClient.shared.cached(videoId: videoId, categories: categories) {
            arm(webView, videoId: videoId, segments: cached)
            return
        }
        lookup?.cancel()
        lookup = Task { [weak self, weak webView] in
            let segments = await SponsorBlockClient.shared.segments(
                videoId: videoId,
                categories: categories
            )
            guard let self, let webView, !Task.isCancelled else { return }
            // The user may have navigated on while the lookup was in flight; arming then would skip
            // parts of a different video.
            if self.armedVideoId == videoId {
                self.arm(webView, videoId: videoId, segments: segments)
            }
        }
    }

    /// Arms or disarms the ad skipper for whatever page `webView` is on.
    ///
    /// Unlike SponsorBlock this is per-document, not per-video: one armed poll covers every video
    /// reached by an in-page navigation afterwards, and the script's own guard makes the repeat call
    /// on each navigation cheap. It is also armed on pages that are not a single video — the feed and
    /// search results carry ad slots of their own — so it runs before the `videoId` check rather
    /// than after it.
    private func applyAdSkip(_ webView: WKWebView, url: String) {
        if YouTubeSettings.adSkipAvailable && settings.adSkipEnabled && YouTubeUrls.isYouTube(url) {
            adSkipArmed = true
            Task { await Self.evaluate(webView, YouTubeAdSkip.script()) }
        } else if adSkipArmed {
            adSkipArmed = false
            Task { await Self.evaluate(webView, YouTubeAdSkip.clearScript()) }
        }
    }

    /// Drops any in-flight work. The enhancer can still be reused afterwards.
    public func cancel() {
        lookup?.cancel()
        qualityTask?.cancel()
    }

    private func arm(_ webView: WKWebView, videoId: String, segments: [SponsorSegment]) {
        // An empty list is still sent: it replaces the previous video's segments, which would
        // otherwise stay live in the page and cut into this one at the same timestamps.
        Task {
            await Self.evaluate(webView, SponsorBlock.script(videoId: videoId, segments: segments))
        }
    }

    @discardableResult
    private static func evaluate(_ webView: WKWebView, _ script: String) async -> String? {
        let result = try? await webView.evaluateJavaScript(script)
        return result as? String
    }
}
