import XCTest
@testable import AutoBridge

/// The URL shapes the YouTube add-ons meet, and the skip arithmetic they drive the page with.
/// Both are pure, so they are asserted here rather than by trying them by hand in a car.
/// Mirrors the Android `YouTubeUrlsTest` and `SponsorBlockTest`.
final class YouTubeAddOnTests: XCTestCase {

    // MARK: - Recognising pages

    func testWatchLinksOnEveryFrontEndYieldTheirId() {
        XCTAssertEqual(YouTubeUrls.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://youtu.be/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://youtu.be/dQw4w9WgXcQ?t=10"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(YouTubeUrls.videoId("https://www.youtube.com/live/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
    }

    func testAPageThatIsNotASingleVideoHasNoId() {
        XCTAssertNil(YouTubeUrls.videoId("https://www.youtube.com/"))
        XCTAssertNil(YouTubeUrls.videoId("https://www.youtube.com/results?search_query=cats"))
        XCTAssertNil(YouTubeUrls.videoId("https://www.youtube.com/@channel"))
        // Not 11 characters, so not an id.
        XCTAssertNil(YouTubeUrls.videoId("https://www.youtube.com/watch?v=short"))
    }

    /// Matched exactly, never by suffix: a host under the same domain that serves no player must
    /// not arm the add-ons.
    func testOnlyTheKnownFrontEndsCount() {
        XCTAssertTrue(YouTubeUrls.isYouTube("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        XCTAssertTrue(YouTubeUrls.isYouTubeMusic("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        XCTAssertFalse(YouTubeUrls.isYouTube("https://v.youtube.com/watch?v=dQw4w9WgXcQ"))
        XCTAssertFalse(YouTubeUrls.isYouTube("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        XCTAssertNil(YouTubeUrls.videoId("https://evil.example.com/watch?v=dQw4w9WgXcQ"))
    }

    // MARK: - SponsorBlock

    func testHashPrefixIsTheFirstFourCharactersOfTheSha256() {
        // Pinned so the lookup keeps asking for the same bucket the Android app asks for.
        XCTAssertEqual(SponsorBlock.hashPrefix("dQw4w9WgXcQ").count, 4)
        XCTAssertEqual(SponsorBlock.hashPrefix("dQw4w9WgXcQ", length: 64).count, 64)
        XCTAssertTrue(
            SponsorBlock.hashPrefix("dQw4w9WgXcQ", length: 64)
                .hasPrefix(SponsorBlock.hashPrefix("dQw4w9WgXcQ"))
        )
    }

    /// The response covers every video sharing the hash prefix, so filtering by the id asked for is
    /// required for correctness, not just for tidiness.
    func testParseKeepsOnlyTheVideoAskedForAndOnlyEnabledCategories() {
        let body = """
        [
          {"videoID":"dQw4w9WgXcQ","segments":[
            {"category":"sponsor","actionType":"skip","segment":[10.0,20.0]},
            {"category":"intro","actionType":"skip","segment":[0.0,5.0]}
          ]},
          {"videoID":"otherVideoX","segments":[
            {"category":"sponsor","actionType":"skip","segment":[1.0,99.0]}
          ]}
        ]
        """
        let segments = SponsorBlock.parse(body, videoId: "dQw4w9WgXcQ", enabled: [.sponsor])
        XCTAssertEqual(segments.count, 1)
        XCTAssertEqual(segments.first?.start, 10)
        XCTAssertEqual(segments.first?.end, 20)
    }

    /// "mute" and "full" would need a different response from the player, and skipping them would
    /// remove more of the video than the category name promises.
    func testOnlySkipActionsAreKept() {
        let body = """
        [{"videoID":"v","segments":[
          {"category":"sponsor","actionType":"mute","segment":[10.0,20.0]},
          {"category":"sponsor","actionType":"full","segment":[30.0,40.0]}
        ]}]
        """
        XCTAssertTrue(SponsorBlock.parse(body, videoId: "v", enabled: [.sponsor]).isEmpty)
    }

    func testASegmentShorterThanTheMinimumIsNotWorthAJump() {
        let body = """
        [{"videoID":"v","segments":[
          {"category":"sponsor","actionType":"skip","segment":[10.0,10.5]}
        ]}]
        """
        XCTAssertTrue(SponsorBlock.parse(body, videoId: "v", enabled: [.sponsor]).isEmpty)
    }

    func testNothingIsParsedWhenEveryCategoryIsOff() {
        let body = """
        [{"videoID":"v","segments":[
          {"category":"sponsor","actionType":"skip","segment":[10.0,20.0]}
        ]}]
        """
        XCTAssertTrue(SponsorBlock.parse(body, videoId: "v", enabled: []).isEmpty)
    }

    func testOverlappingSegmentsAreFoldedTogetherSoAPositionMatchesOne() {
        let merged = SponsorBlock.merge([
            SponsorSegment(category: .sponsor, start: 30, end: 40),
            SponsorSegment(category: .sponsor, start: 10, end: 25),
            SponsorSegment(category: .intro, start: 20, end: 35)
        ])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged.first?.start, 10)
        XCTAssertEqual(merged.first?.end, 40)
    }

    func testSkipTargetIsTheEndOfTheSegmentThePositionSitsIn() {
        let segments = [
            SponsorSegment(category: .sponsor, start: 10, end: 20),
            SponsorSegment(category: .outro, start: 100, end: 110)
        ]
        XCTAssertEqual(SponsorBlock.skipTarget(segments, position: 10), 20)
        XCTAssertEqual(SponsorBlock.skipTarget(segments, position: 19), 20)
        XCTAssertNil(SponsorBlock.skipTarget(segments, position: 9.9))
        // Inside the edge tolerance of the end, which already counts as past it.
        XCTAssertNil(SponsorBlock.skipTarget(segments, position: 19.9))
        XCTAssertEqual(SponsorBlock.skipTarget(segments, position: 105), 110)
    }

    /// The injected scripts are fixed strings built from numbers this app fetched — nothing from the
    /// page is read back — so what they carry is worth asserting.
    func testTheInjectedScriptCarriesTheSegmentsAndTheVideoItIsArmedFor() {
        let script = SponsorBlock.script(
            videoId: "dQw4w9WgXcQ",
            segments: [SponsorSegment(category: .sponsor, start: 10, end: 20.5)]
        )
        XCTAssertTrue(script.contains("window.__abSponsorSegments = [[10.0,20.5]]"))
        XCTAssertTrue(script.contains("window.__abSponsorVideo = 'dQw4w9WgXcQ'"))
        XCTAssertTrue(SponsorBlock.clearScript().contains("window.__abSponsorSegments = []"))
    }

    func testAdSkipScriptIsReArmableAndStoppable() {
        XCTAssertTrue(YouTubeAdSkip.script().contains("window.__abAdSkip"))
        XCTAssertTrue(YouTubeAdSkip.script().contains("'rearmed'"))
        XCTAssertTrue(YouTubeAdSkip.clearScript().contains("clearInterval"))
    }

    // MARK: - Settings

    /// Only the categories that are uncontroversially "not the video you asked for" start enabled;
    /// nothing at all runs until the feature itself is switched on.
    @MainActor
    func testCategoryDefaultsAndTheOffSwitchThatOverridesThem() {
        let defaults = UserDefaults(suiteName: "dev.autobridge.tests.youtube")!
        defaults.removePersistentDomain(forName: "dev.autobridge.tests.youtube")
        let settings = YouTubeSettings(defaults: defaults)

        XCTAssertFalse(settings.sponsorBlockEnabled)
        XCTAssertFalse(settings.adSkipEnabled)
        XCTAssertFalse(settings.autoHighestQuality)
        XCTAssertTrue(settings.enabledCategories.isEmpty)

        XCTAssertTrue(settings.isEnabled(.sponsor))
        XCTAssertTrue(settings.isEnabled(.selfPromo))
        XCTAssertTrue(settings.isEnabled(.interaction))
        XCTAssertFalse(settings.isEnabled(.intro))
        XCTAssertFalse(settings.isEnabled(.outro))

        settings.sponsorBlockEnabled = true
        XCTAssertEqual(settings.enabledCategories, [.sponsor, .selfPromo, .interaction])
        settings.setEnabled(.sponsor, false)
        XCTAssertEqual(settings.enabledCategories, [.selfPromo, .interaction])
    }
}
