import XCTest
@testable import AutoBridge

/// End-to-end load of a real public playlist, over the real network.
///
/// Everything else in this target is pure and runs anywhere. This one reaches the internet, so it
/// is **off unless asked for** — a test that fails because a community playlist moved is a test that
/// stops meaning anything. Run it deliberately:
///
///     AUTOBRIDGE_NETWORK_TESTS=1 xcodebuild test -scheme AutoBridge \
///       -destination 'platform=iOS Simulator,name=iPhone 17'
///
/// What it proves is the whole seam the unit tests cannot: that a seeded default address still
/// answers, that the body decodes, and that `M3UParser` → `IptvPlaylistConventions` → `IptvLogos` →
/// `XtreamClient.catalog` turns it into something the screens can draw.
final class PublicPlaylistLiveTests: XCTestCase {

    private var enabled: Bool {
        ProcessInfo.processInfo.environment["AUTOBRIDGE_NETWORK_TESTS"] == "1"
    }

    func testEverySeededDefaultStillLoadsIntoACatalog() async throws {
        try XCTSkipUnless(enabled, "Set AUTOBRIDGE_NETWORK_TESTS=1 to run the live playlist check.")

        for entry in IptvDirectory.defaults() {
            let source = IptvDirectory.toSource(entry, id: "live-\(entry.kind.rawValue)")
            let catalog = try await XtreamClient.load(source)

            XCTAssertFalse(catalog.entries.isEmpty, "\(entry.name) returned no entries")
            XCTAssertEqual(catalog.categories.first?.id, IptvCatalogData.allCategoryId)
            XCTAssertEqual(catalog.categories.first?.count, catalog.entries.count)
            // Every address the mapping kept has to be openable by something: a stream for the
            // player, a watch page for the browser.
            XCTAssertTrue(catalog.entries.allSatisfy { !$0.url.isEmpty })
            // A logo that survived `IptvLogos.resolve` is fetchable or it is blank; nothing else.
            XCTAssertTrue(
                catalog.entries.allSatisfy {
                    $0.logo.isEmpty
                        || $0.logo.hasPrefix("http://")
                        || $0.logo.hasPrefix("https://")
                }
            )
            print("\(entry.name): \(catalog.entries.count) entries, "
                + "\(catalog.categories.count - 1) categories, "
                + "\(catalog.entries.filter { !$0.logo.isEmpty }.count) with logos, "
                + "\(catalog.entries.filter(\.isWebPage).count) watch pages")
        }
    }
}
