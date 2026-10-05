import Foundation

/// A page the user saved in the in-app browser.
public struct WebBookmark: Codable, Hashable, Identifiable {
    public let title: String
    public let url: String

    public var id: String { url }

    public init(title: String, url: String) {
        self.title = title
        self.url = url
    }
}

/// Saved browser pages, listed alongside starred channels in Favorites.
///
/// Mirrors the Android `WebBookmarkStore`. These are public addresses with no credentials in them,
/// so unlike the source and history stores this one stays in `UserDefaults`.
@MainActor
public final class WebBookmarkStore: ObservableObject {
    @Published public private(set) var bookmarks: [WebBookmark] = []

    private let defaults: UserDefaults
    private let key = "web.bookmarks.v1"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode([WebBookmark].self, from: data) {
            bookmarks = decoded
        }
    }

    public func contains(url: String) -> Bool {
        bookmarks.contains { $0.url == url }
    }

    /// Adds or removes `url`. Returns true when it is saved afterwards.
    @discardableResult
    public func toggle(title: String, url: String) -> Bool {
        guard !url.isEmpty else { return false }
        if let index = bookmarks.firstIndex(where: { $0.url == url }) {
            bookmarks.remove(at: index)
            persist()
            return false
        }
        bookmarks.append(WebBookmark(title: title.isEmpty ? url : title, url: url))
        persist()
        return true
    }

    public func remove(url: String) {
        bookmarks.removeAll { $0.url == url }
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(bookmarks) else { return }
        defaults.set(data, forKey: key)
    }
}
