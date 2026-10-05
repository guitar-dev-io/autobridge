import Foundation

/// The YouTube add-on settings, in the app's own defaults.
///
/// Everything here is off unless the user turns it on. Skipping parts of a video and overriding the
/// quality a site chose are both changes to what someone asked to watch, and the network traffic
/// SponsorBlock adds goes to a third party, so none of it may happen by default. Skipping ads is off
/// for a further reason: YouTube detects it and may answer with an interstitial of its own, which is
/// a trade only the person watching can agree to. Mirrors the Android `YouTubeSettings`.
@MainActor
public final class YouTubeSettings: ObservableObject {
    @Published public var sponsorBlockEnabled: Bool {
        didSet { defaults.set(sponsorBlockEnabled, forKey: Keys.sponsorBlock) }
    }
    @Published public var adSkipEnabled: Bool {
        didSet { defaults.set(adSkipEnabled, forKey: Keys.adSkip) }
    }
    @Published public var autoHighestQuality: Bool {
        didSet { defaults.set(autoHighestQuality, forKey: Keys.highestQuality) }
    }
    /// Bumped when a category is toggled, so a view reading `isEnabled` re-renders.
    @Published public private(set) var categoryRevision = 0

    private enum Keys {
        static let sponsorBlock = "youtube.sponsorBlock"
        static let adSkip = "youtube.adSkip"
        static let highestQuality = "youtube.autoHighestQuality"
        static let categoryPrefix = "youtube.sponsorCategory."
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.sponsorBlockEnabled = defaults.bool(forKey: Keys.sponsorBlock)
        self.adSkipEnabled = defaults.bool(forKey: Keys.adSkip)
        self.autoHighestQuality = defaults.bool(forKey: Keys.highestQuality)
    }

    public func isEnabled(_ category: SponsorCategory) -> Bool {
        let key = Keys.categoryPrefix + category.apiId
        guard defaults.object(forKey: key) != nil else { return category.onByDefault }
        return defaults.bool(forKey: key)
    }

    public func setEnabled(_ category: SponsorCategory, _ value: Bool) {
        defaults.set(value, forKey: Keys.categoryPrefix + category.apiId)
        categoryRevision += 1
    }

    /// The categories a lookup should ask for; empty when the feature is off.
    public var enabledCategories: Set<SponsorCategory> {
        guard sponsorBlockEnabled else { return [] }
        return Set(SponsorCategory.allCases.filter(isEnabled))
    }

    /// Whether any add-on is armed, for the row that summarises them.
    public var anyEnabled: Bool {
        sponsorBlockEnabled || adSkipEnabled || autoHighestQuality
    }
}
