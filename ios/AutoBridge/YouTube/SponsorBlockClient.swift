import Foundation

/// Lookups against the public SponsorBlock database, with a small in-memory cache.
///
/// The request carries only a four-character hash prefix of the video id (see
/// `SponsorBlock.hashPrefix`), never the id itself and never anything about the user, so the server
/// learns that someone is watching one of thousands of videos rather than which one.
/// Mirrors the Android `SponsorBlockClient`.
public final class SponsorBlockClient: @unchecked Sendable {
    public static let shared = SponsorBlockClient()

    private static let endpoint = "https://sponsor.ajay.app/api/skipSegments"
    private static let maxBodyBytes = 512 * 1024
    private static let attempts = 2
    private static let retryDelay: UInt64 = 1_500_000_000

    private let lock = NSLock()
    /// Keyed by video id and the categories asked for, so toggling a category re-queries.
    private var cache: [String: [SponsorSegment]] = [:]

    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 16
        return URLSession(configuration: configuration)
    }()

    private init() {}

    public func cached(videoId: String, categories: Set<SponsorCategory>) -> [SponsorSegment]? {
        lock.withLock { cache[key(videoId: videoId, categories: categories)] }
    }

    /// Returns an empty list when nothing is known. Failures are not cached, so opening the same
    /// video again asks again; nothing retries on its own beyond `attempts`, because polling would
    /// hammer a public service.
    public func segments(videoId: String, categories: Set<SponsorCategory>) async -> [SponsorSegment] {
        if categories.isEmpty { return [] }
        if let cached = cached(videoId: videoId, categories: categories) { return cached }

        // Quotes are percent-encoded too: a raw `"` is not legal in a query string, and whether it
        // survives depends on every proxy between the head unit and the server.
        let query = categories
            .sorted { $0.apiId < $1.apiId }
            .map { "%22\($0.apiId)%22" }
            .joined(separator: "%2C")
        let url = "\(Self.endpoint)/\(SponsorBlock.hashPrefix(videoId))"
            + "?categories=%5B\(query)%5D&actionTypes=%5B%22skip%22%5D"

        // One retry: a car's connection drops for a second at a time, and a single miss would
        // otherwise leave the whole video unskipped.
        for attempt in 0..<Self.attempts {
            if let body = try? await fetch(url) {
                let segments = SponsorBlock.parse(body, videoId: videoId, enabled: categories)
                lock.withLock { cache[key(videoId: videoId, categories: categories)] = segments }
                return segments
            }
            if attempt + 1 < Self.attempts {
                try? await Task.sleep(nanoseconds: Self.retryDelay)
            }
        }
        return []
    }

    private func key(videoId: String, categories: Set<SponsorCategory>) -> String {
        videoId + "|" + categories.map(\.apiId).sorted().joined(separator: ",")
    }

    private func fetch(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse {
            // 404 is the ordinary answer for "no segments for this prefix", not a failure.
            if http.statusCode == 404 { return "[]" }
            if !(200...299).contains(http.statusCode) { throw URLError(.badServerResponse) }
        }
        // A public endpoint is not trusted to send something this device should hold whole.
        if data.count > Self.maxBodyBytes { throw URLError(.dataLengthExceedsMaximum) }
        return String(data: data, encoding: .utf8) ?? "[]"
    }
}
