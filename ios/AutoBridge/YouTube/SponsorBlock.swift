import CryptoKit
import Foundation

/// The crowd-sourced segment categories the SponsorBlock database serves.
///
/// `apiId` is the identifier the API speaks; `onByDefault` is what a user who turns the feature on
/// without opening the category list gets. Only the categories that are uncontroversially "not the
/// video you asked for" start enabled — skipping the intro or the outro of every video by default
/// would silently cut content people came to watch.
public enum SponsorCategory: String, CaseIterable, Identifiable, Hashable {
    case sponsor
    case selfPromo
    case interaction
    case intro
    case outro
    case preview
    case musicOfftopic
    case filler

    public var id: String { apiId }

    public var apiId: String {
        switch self {
        case .sponsor: return "sponsor"
        case .selfPromo: return "selfpromo"
        case .interaction: return "interaction"
        case .intro: return "intro"
        case .outro: return "outro"
        case .preview: return "preview"
        case .musicOfftopic: return "music_offtopic"
        case .filler: return "filler"
        }
    }

    public var label: String {
        switch self {
        case .sponsor: return NSLocalizedString("Sponsor", comment: "SponsorBlock category")
        case .selfPromo: return NSLocalizedString("Self-promotion", comment: "SponsorBlock category")
        case .interaction: return NSLocalizedString("Interaction reminder", comment: "SponsorBlock category")
        case .intro: return NSLocalizedString("Intro", comment: "SponsorBlock category")
        case .outro: return NSLocalizedString("Outro", comment: "SponsorBlock category")
        case .preview: return NSLocalizedString("Preview / recap", comment: "SponsorBlock category")
        case .musicOfftopic: return NSLocalizedString("Non-music section", comment: "SponsorBlock category")
        case .filler: return NSLocalizedString("Filler", comment: "SponsorBlock category")
        }
    }

    public var caption: String {
        switch self {
        case .sponsor: return NSLocalizedString("Paid promotion inside the video", comment: "SponsorBlock category")
        case .selfPromo: return NSLocalizedString("Merch, Patreon, the creator's other channels", comment: "SponsorBlock category")
        case .interaction: return NSLocalizedString("\"Like and subscribe\"", comment: "SponsorBlock category")
        case .intro: return NSLocalizedString("Opening animation or title card", comment: "SponsorBlock category")
        case .outro: return NSLocalizedString("End cards and credits", comment: "SponsorBlock category")
        case .preview: return NSLocalizedString("A summary of what is coming or what happened", comment: "SponsorBlock category")
        case .musicOfftopic: return NSLocalizedString("Talking in a music video", comment: "SponsorBlock category")
        case .filler: return NSLocalizedString("Tangents and jokes that add no information", comment: "SponsorBlock category")
        }
    }

    public var onByDefault: Bool {
        switch self {
        case .sponsor, .selfPromo, .interaction: return true
        case .intro, .outro, .preview, .musicOfftopic, .filler: return false
        }
    }

    public static func byApiId(_ id: String) -> SponsorCategory? {
        allCases.first { $0.apiId == id }
    }
}

/// One stretch of a video the database says can be skipped. Times are seconds.
public struct SponsorSegment: Equatable {
    public let category: SponsorCategory
    public let start: Double
    public let end: Double

    public init(category: SponsorCategory, start: Double, end: Double) {
        self.category = category
        self.start = start
        self.end = end
    }

    public var length: Double { end - start }
}

/// Parsing and skip arithmetic for SponsorBlock, kept free of the network so the behaviour that
/// matters — which segment applies at a given moment, and what the page is told to do about it — is
/// asserted in a unit test instead of on a head unit. Mirrors the Android `SponsorBlock`.
public enum SponsorBlock {
    /// A segment shorter than this is not worth a jump: the seek itself costs more than the
    /// segment, and on a live-ish HLS buffer it can land the player back where it started.
    public static let minSegmentSeconds = 1.0

    /// How close to the end of a segment still counts as inside it.
    private static let edgeTolerance = 0.25

    /// How many times one segment may be seeked past before it is given up on. A seek that lands
    /// normally needs one; a seek the player refuses would otherwise be re-issued four times a
    /// second for the whole segment.
    private static let maxSkipAttempts = 4

    /// The API is queried by the first four characters of the SHA-256 of the video id, so the
    /// server is told a bucket of roughly 1 in 65,536 videos rather than which video is playing.
    /// The exact match is then made locally in `parse`.
    public static func hashPrefix(_ videoId: String, length: Int = 4) -> String {
        let digest = SHA256.hash(data: Data(videoId.utf8))
        return String(digest.map { String(format: "%02x", $0) }.joined().prefix(length))
    }

    /// Reads the `skipSegments` response, keeping only `enabled` categories of the one video asked
    /// for. The response covers every video sharing the hash prefix, so filtering by `videoId` is
    /// required for correctness, not just for tidiness.
    public static func parse(
        _ body: String,
        videoId: String,
        enabled: Set<SponsorCategory>
    ) -> [SponsorSegment] {
        if enabled.isEmpty { return [] }
        guard let data = body.data(using: .utf8),
              let videos = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        var segments: [SponsorSegment] = []
        for video in videos {
            guard (video["videoID"] as? String) == videoId else { continue }
            guard let list = video["segments"] as? [[String: Any]] else { continue }
            for item in list {
                // "skip" is the only action this app performs. "mute" and "full" would need a
                // different response from the player, and silently skipping them would remove more
                // of the video than the category name promises.
                let action = item["actionType"] as? String ?? "skip"
                if action != "skip" { continue }
                guard let categoryId = item["category"] as? String,
                      let category = SponsorCategory.byApiId(categoryId),
                      enabled.contains(category) else { continue }
                guard let bounds = item["segment"] as? [Any], bounds.count >= 2,
                      let start = (bounds[0] as? NSNumber)?.doubleValue,
                      let end = (bounds[1] as? NSNumber)?.doubleValue else { continue }
                if !start.isFinite || !end.isFinite || end - start < minSegmentSeconds { continue }
                segments.append(SponsorSegment(category: category, start: start, end: end))
            }
        }
        return merge(segments)
    }

    /// Sorts by start time and folds overlapping segments together, so a position can never match
    /// two entries and a skip can never land inside the next one.
    public static func merge(_ segments: [SponsorSegment]) -> [SponsorSegment] {
        var merged: [SponsorSegment] = []
        for segment in segments.sorted(by: { $0.start < $1.start }) {
            if let last = merged.last, segment.start <= last.end {
                if segment.end > last.end {
                    merged[merged.count - 1] = SponsorSegment(
                        category: last.category,
                        start: last.start,
                        end: segment.end
                    )
                }
            } else {
                merged.append(segment)
            }
        }
        return merged
    }

    /// Where playback should jump to from `position`, or nil to keep playing. Mirrors exactly what
    /// the injected script does, so the rule is testable here rather than only in a page.
    public static func skipTarget(_ segments: [SponsorSegment], position: Double) -> Double? {
        segments.first { position >= $0.start && position < $0.end - edgeTolerance }?.end
    }

    /// The script that performs the skipping inside the page.
    ///
    /// It is a fixed string built from numbers this app fetched; nothing from the page is read back
    /// and no message handler is exposed, for the same reason the browser exposes none: it loads
    /// arbitrary sites and none of them should get a handle on the app.
    ///
    /// The listener sits on the document in the capture phase rather than on one `<video>`:
    /// `timeupdate` does not bubble but is still captured, so a player built after the script runs,
    /// or one YouTube swaps out between videos, is covered without a retry.
    public static func script(videoId: String, segments: [SponsorSegment]) -> String {
        let list = segments.map { "[\($0.start),\($0.end)]" }.joined(separator: ",")
        return """
        (function(){
          // A new video invalidates the per-segment attempt counts, which are indexed into the
          // list being replaced here.
          if (window.__abSponsorVideo !== '\(videoId)') window.__abSponsorTries = {};
          window.__abSponsorSegments = [\(list)];
          window.__abSponsorVideo = '\(videoId)';
          if (window.__abSponsorArmed) return 'updated';
          window.__abSponsorArmed = true;
          document.addEventListener('timeupdate', function(e){
            var v = e.target;
            if (!v || v.tagName !== 'VIDEO') return;
            var id = window.__abSponsorVideo;
            if (!id || location.href.indexOf(id) === -1) return;
            if (document.querySelector('.ad-showing')) return;
            var list = window.__abSponsorSegments || [];
            var tries = window.__abSponsorTries || (window.__abSponsorTries = {});
            var t = v.currentTime;
            for (var i = 0; i < list.length; i++) {
              if (t < list[i][0] || t >= list[i][1] - \(edgeTolerance)) continue;
              var n = (tries[i] || 0) + 1;
              tries[i] = n;
              if (n > \(maxSkipAttempts)) return;
              var p = document.getElementById('movie_player') ||
                      document.querySelector('.html5-video-player');
              if (p && p.seekTo) {
                try { p.seekTo(list[i][1], true); return; } catch (err) {}
              }
              v.currentTime = list[i][1];
              return;
            }
          }, true);
          return 'armed';
        })();
        """
    }

    /// Clears any armed segment list, for when the feature is switched off mid-page.
    public static func clearScript() -> String {
        "(function(){ window.__abSponsorSegments = []; window.__abSponsorVideo = ''; "
            + "window.__abSponsorTries = {}; return 'cleared'; })();"
    }

    /// Asks the page's own player for its best quality.
    ///
    /// `getAvailableQualityLevels()` is ordered highest first, so the first entry is the target.
    /// Both setters are called because the mobile and desktop players have disagreed about which
    /// one sticks; the range form is what survives an adaptive downgrade.
    public static func highestQualityScript() -> String {
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
        """
    }
}
