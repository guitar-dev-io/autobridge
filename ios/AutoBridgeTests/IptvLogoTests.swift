import XCTest
@testable import AutoBridge

/// The logo shapes real playlists hand out. `IptvLogos` and `M3UParser` are both free of UIKit, so
/// every one of them is pinned down here without a device or a network. Mirrors the Android
/// `IptvLogoTest`.
final class IptvLogoTests: XCTestCase {

    private let playlist = "https://lists.example.com/iptv/playlists/thai.m3u"

    // MARK: - Which attribute carries the logo

    func testReadsTheCanonicalAttribute() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://cdn.example.com/a.png" group-title="News",Channel A
            https://stream.example.com/a.m3u8
            """
        )
        XCTAssertEqual(channels.first?.logo, "https://cdn.example.com/a.png")
    }

    func testFallsBackToTheOtherSpellingsPanelsEmit() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 logo="https://cdn.example.com/b.png",Channel B
            https://stream.example.com/b.m3u8
            #EXTINF:-1 url-logo="https://cdn.example.com/c.png",Channel C
            https://stream.example.com/c.m3u8
            #EXTINF:-1 tvg-logo-small="https://cdn.example.com/d.png",Channel D
            https://stream.example.com/d.m3u8
            """
        )
        XCTAssertEqual(
            channels.map(\.logo),
            [
                "https://cdn.example.com/b.png",
                "https://cdn.example.com/c.png",
                "https://cdn.example.com/d.png"
            ]
        )
    }

    func testTheCanonicalAttributeWinsWhenALineCarriesTwo() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 logo="https://cdn.example.com/wrong.png" tvg-logo="https://cdn.example.com/right.png",E
            https://stream.example.com/e.m3u8
            """
        )
        XCTAssertEqual(channels.first?.logo, "https://cdn.example.com/right.png")
    }

    func testExtImgLineCarriesTheLogoWithoutOverridingAnAttribute() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel F
            #EXTIMG:https://cdn.example.com/f.png
            https://stream.example.com/f.m3u8
            #EXTINF:-1 tvg-logo="https://cdn.example.com/attr.png",Channel G
            #EXTIMG:https://cdn.example.com/line.png
            https://stream.example.com/g.m3u8
            """
        )
        XCTAssertEqual(
            channels.map(\.logo),
            ["https://cdn.example.com/f.png", "https://cdn.example.com/attr.png"]
        )
    }

    // MARK: - What makes an address usable

    func testAnAbsoluteAddressIsLeftAlone() {
        XCTAssertEqual(
            IptvLogos.resolve(base: playlist, raw: "http://cdn.example.com/a.png"),
            "http://cdn.example.com/a.png"
        )
    }

    func testAProtocolRelativeAddressTakesHttps() {
        XCTAssertEqual(
            IptvLogos.resolve(base: playlist, raw: "//cdn.example.com/a.png"),
            "https://cdn.example.com/a.png"
        )
    }

    func testAHostRelativePathResolvesAgainstThePlaylistHost() {
        XCTAssertEqual(
            IptvLogos.resolve(base: playlist, raw: "/logos/a.png"),
            "https://lists.example.com/logos/a.png"
        )
    }

    func testAPathRelativeToThePlaylistResolvesAgainstItsDirectory() {
        XCTAssertEqual(
            IptvLogos.resolve(base: playlist, raw: "logos/a.png"),
            "https://lists.example.com/iptv/playlists/logos/a.png"
        )
    }

    func testAQueryOnThePlaylistAddressIsNotPartOfItsDirectory() {
        XCTAssertEqual(
            IptvLogos.resolve(
                base: "http://portal.tv/get.php?username=u&password=p",
                raw: "logos/a.png"
            ),
            "http://portal.tv/logos/a.png"
        )
    }

    func testASchemeTheLoaderCannotFetchIsDropped() {
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "data:image/png;base64,iVBORw0KGgo="), "")
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "file:///var/mobile/a.png"), "")
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "ftp://files.example.com/a.png"), "")
    }

    func testTheValuesAGeneratorWritesToMeanNoLogoAreDropped() {
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: ""), "")
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "   "), "")
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "null"), "")
        XCTAssertEqual(IptvLogos.resolve(base: playlist, raw: "N/A"), "")
    }

    func testARelativeLogoIsDroppedWhenTheBaseSaysNothing() {
        XCTAssertEqual(IptvLogos.resolve(base: "", raw: "logos/a.png"), "")
        XCTAssertEqual(IptvLogos.resolve(base: "not-a-url", raw: "/logos/a.png"), "")
    }

    // MARK: - Through the catalog mapping

    func testCatalogResolvesEveryLogoAgainstThePlaylistItCameFrom() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="logos/one.png" group-title="Thai",One
            http://stream.example.com/1.ts
            #EXTINF:-1 tvg-logo="//cdn.example.com/two.png",Two
            http://stream.example.com/2.ts
            """
        )
        let catalog = XtreamClient.catalog(from: channels, base: playlist, keepOnlyRadio: false)
        XCTAssertEqual(
            catalog.entries.map(\.logo),
            [
                "https://lists.example.com/iptv/playlists/logos/one.png",
                "https://cdn.example.com/two.png"
            ]
        )
    }

    /// A blank `group-title` goes into the "All" bucket rather than into a category of its own, and
    /// "All" always leads the list — the same shape the Android `finish` step produces.
    func testCatalogPutsUngroupedChannelsInTheAllBucketAndCountsCategories() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",A
            http://stream.example.com/a.ts
            #EXTINF:-1 group-title="News",B
            http://stream.example.com/b.ts
            #EXTINF:-1,C
            http://stream.example.com/c.ts
            """
        )
        let catalog = XtreamClient.catalog(from: channels, base: playlist, keepOnlyRadio: false)
        XCTAssertEqual(catalog.categories.first?.id, IptvCatalogData.allCategoryId)
        XCTAssertEqual(catalog.categories.first?.count, 3)
        XCTAssertEqual(catalog.categories.count, 2)
        XCTAssertEqual(catalog.categories.last?.name, "News")
        XCTAssertEqual(catalog.categories.last?.count, 2)
        XCTAssertEqual(catalog.entries(in: IptvCatalogData.allCategoryId).count, 3)
    }

    /// The audio heuristic is for a mixed portal playlist only, which is why it is a parameter
    /// rather than something the mapping decides for itself.
    func testRadioFilterKeepsAudioAndOnlyWhenAsked() {
        let channels = M3UParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Music",Cool FM
            http://stream.example.com/fm.aac
            #EXTINF:-1 group-title="Sports",Match Day
            http://stream.example.com/match.ts
            """
        )
        let filtered = XtreamClient.catalog(from: channels, base: playlist, keepOnlyRadio: true)
        XCTAssertEqual(filtered.entries.map(\.title), ["Cool FM"])
        let unfiltered = XtreamClient.catalog(from: channels, base: playlist, keepOnlyRadio: false)
        XCTAssertEqual(unfiltered.entries.count, 2)
    }
}
