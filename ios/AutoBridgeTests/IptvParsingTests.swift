import XCTest
@testable import AutoBridge

/// Pure-parsing coverage for the ported IPTV source layer. Mirrors the Android
/// `IptvParsingTest` so the two platforms agree on the URL shapes providers hand out.
final class IptvParsingTests: XCTestCase {

    // MARK: - Xtream credentials

    func testParsesFullPlayerApiLink() {
        let credentials = XtreamCredentials.parse(
            input: "http://portal.example.com:8080/player_api.php?username=alice&password=s3cret"
        )
        XCTAssertEqual(credentials?.portal, "http://portal.example.com:8080")
        XCTAssertEqual(credentials?.username, "alice")
        XCTAssertEqual(credentials?.password, "s3cret")
    }

    func testParsesGetPhpPlaylistLink() {
        let credentials = XtreamCredentials.parse(
            input: "http://host.tv/get.php?username=bob&password=pw&type=m3u_plus&output=ts"
        )
        XCTAssertEqual(credentials?.portal, "http://host.tv")
        XCTAssertEqual(credentials?.username, "bob")
    }

    func testKeepsDirectoryPrefixButDropsEndpointFile() {
        let credentials = XtreamCredentials.parse(
            input: "http://host.tv/iptv/player_api.php?username=u&password=p"
        )
        XCTAssertEqual(credentials?.portal, "http://host.tv/iptv")
    }

    func testTypedCredentialsWinOverEmbedded() {
        let credentials = XtreamCredentials.parse(
            input: "http://host.tv/player_api.php?username=stale&password=old",
            username: "fresh",
            password: "new"
        )
        XCTAssertEqual(credentials?.username, "fresh")
        XCTAssertEqual(credentials?.password, "new")
    }

    func testBarePortalWithoutCredentialsRejected() {
        XCTAssertNil(XtreamCredentials.parse(input: "http://host.tv"))
    }

    func testBareHostDefaultsToHttp() {
        let credentials = XtreamCredentials.parse(input: "host.tv:8080", username: "u", password: "p")
        XCTAssertEqual(credentials?.portal, "http://host.tv:8080")
    }

    func testNonHttpSchemeRejected() {
        XCTAssertNil(XtreamCredentials.parse(input: "ftp://host.tv/player_api.php?username=u&password=p"))
    }

    func testStreamUrlsEscapeReservedCharacters() {
        let credentials = XtreamCredentials(portal: "http://host.tv", username: "a b", password: "p/w")
        XCTAssertEqual(credentials.liveUrl(streamId: "42"), "http://host.tv/live/a%20b/p%2Fw/42.m3u8")
        XCTAssertEqual(credentials.vodUrl(streamId: "7", extension: "mkv"), "http://host.tv/movie/a%20b/p%2Fw/7.mkv")
    }

    func testVodFallsBackToMp4() {
        let url = XtreamCredentials(portal: "http://h", username: "u", password: "p")
            .vodUrl(streamId: "1", extension: "")
        XCTAssertTrue(url.hasSuffix("/1.mp4"))
    }

    // MARK: - M3U parsing

    func testParsesM3uPlusAttributesAndNames() {
        let channels = M3UParser.parse("""
        #EXTM3U
        #EXTINF:-1 tvg-id="one" tvg-logo="http://logo/1.png" group-title="News",Channel One
        http://host/stream/1.ts
        #EXTINF:-1 group-title="Music",Radio Two
        http://host/stream/2.aac
        """)
        XCTAssertEqual(channels.count, 2)
        XCTAssertEqual(channels[0].name, "Channel One")
        XCTAssertEqual(channels[0].group, "News")
        XCTAssertEqual(channels[0].logo, "http://logo/1.png")
        XCTAssertEqual(channels[1].url, "http://host/stream/2.aac")
    }

    func testCommaInsideAttributeDoesNotSplitName() {
        let channels = M3UParser.parse("""
        #EXTM3U
        #EXTINF:-1 group-title="News, World",BBC One HD
        http://host/1
        """)
        XCTAssertEqual(channels.count, 1)
        XCTAssertEqual(channels[0].name, "BBC One HD")
        XCTAssertEqual(channels[0].group, "News, World")
    }

    func testExtGrpRefinesPendingEntry() {
        let channels = M3UParser.parse("""
        #EXTM3U
        #EXTINF:-1,Sport HD
        #EXTGRP:Sports
        http://host/3
        """)
        XCTAssertEqual(channels.count, 1)
        XCTAssertEqual(channels[0].group, "Sports")
    }

    func testExtInfWithoutUrlIsDropped() {
        let channels = M3UParser.parse("#EXTM3U\n#EXTINF:-1,Dangling")
        XCTAssertTrue(channels.isEmpty)
    }

    func testStationListWithNoAttributesAndInterleavedComments() {
        let channels = M3UParser.parse("""
        #EXTM3U
        #RADIOBROWSERUUID:133ed697-1d6d-4b44-ade5-34070ef6aa6d
        #EXTINF:1,101 kiss fm สุราษฏร์ธานี
        http://siamtoday.in.th/8498

        #RADIOBROWSERUUID:153237c9-f6b1-4079-9a78-8063d7730aaf
        #EXTINF:1,101.5 Chula Radio
        http://radio11.plathong.net:7590/;stream.mp3
        """)
        XCTAssertEqual(channels.count, 2)
        XCTAssertEqual(channels[0].name, "101 kiss fm สุราษฏร์ธานี")
        XCTAssertEqual(channels[1].url, "http://radio11.plathong.net:7590/;stream.mp3")
        XCTAssertEqual(channels[1].group, "")
    }

    func testMultiValuedGroupTitleKeptVerbatim() {
        let channels = M3UParser.parse("""
        #EXTM3U
        #EXTINF:-1 tvg-id="AXNAsia.sg@Thailand" group-title="Movies;Series",AXN Asia Thailand (720p)
        http://58.8.186.128:10007/bysid/2814
        """)
        XCTAssertEqual(channels.count, 1)
        XCTAssertEqual(channels[0].group, "Movies;Series")
        XCTAssertEqual(channels[0].name, "AXN Asia Thailand (720p)")
    }

    // MARK: - Free-TV conventions

    func testFreeTvBlockParsesWithMarkersReadOffNames() {
        let channels = M3UParser.parse("""
        #EXTM3U x-tvg-url="https://epgshare01.online/epgshare01/epg_ripper_AL1.xml.gz"
        #EXTINF:-1 tvg-name="Kanali 7" tvg-logo="https://i.imgur.com/rL2v9pM.png" tvg-id="Kanali7.al" tvg-country="AL" group-title="Albania",Kanali 7 Ⓢ
        https://fe.tring.al/delta/105/out/u/1200_1.m3u8
        #EXTINF:-1 tvg-name="ABC News Albania" tvg-logo="https://i.imgur.com/aObcudw.png" tvg-id="ABCNewsAlbania.al" tvg-country="AL" group-title="Albania",ABC News Albania Ⓣ
        https://www.twitch.tv/abcnewsal
        """)
        XCTAssertEqual(channels.count, 2)
        XCTAssertEqual(channels[0].group, "Albania")
        XCTAssertEqual(channels[0].logo, "https://i.imgur.com/rL2v9pM.png")

        let sd = IptvPlaylistConventions.label(channels[0].name)
        XCTAssertEqual(sd.title, "Kanali 7")
        XCTAssertEqual(sd.hints, ["SD"])

        let twitch = IptvPlaylistConventions.label(channels[1].name)
        XCTAssertEqual(twitch.title, "ABC News Albania")
        XCTAssertEqual(twitch.hints, ["Twitch"])
    }

    func testSeveralMarkersOnOneNameAllReported() {
        let label = IptvPlaylistConventions.label("Encuentro Ⓨ Ⓖ")
        XCTAssertEqual(label.title, "Encuentro")
        XCTAssertEqual(label.hints, ["YouTube", "Geo-blocked"])
    }

    func testNameWithoutMarkersLeftAsIs() {
        let label = IptvPlaylistConventions.label("101 kiss fm สุราษฏร์ธานี")
        XCTAssertEqual(label.title, "101 kiss fm สุราษฏร์ธานี")
        XCTAssertTrue(label.hints.isEmpty)
    }

    func testWatchPagesSeparatedFromStreams() {
        XCTAssertTrue(IptvPlaylistConventions.isWebPage("https://www.youtube.com/@EuronewsAlbania/live"))
        XCTAssertTrue(IptvPlaylistConventions.isWebPage("https://www.twitch.tv/topnewsal"))
        XCTAssertTrue(IptvPlaylistConventions.isWebPage("http://youtu.be/abc123"))
        XCTAssertTrue(IptvPlaylistConventions.isWebPage("https://v.youtube.com/abc123"))
        XCTAssertFalse(IptvPlaylistConventions.isWebPage("https://fe.tring.al/delta/105/out/u/1200_1.m3u8"))
    }

    func testEndpointThatIsNotWatchPageStaysStream() {
        XCTAssertFalse(IptvPlaylistConventions.isWebPage("https://sktv.mxnticek.eu/new/stream.php?ch=Nova"))
        XCTAssertFalse(IptvPlaylistConventions.isWebPage(
            "https://mediapolis.rai.it/relinker/relinkerServlet.htm?cont=2606803&output=7"
        ))
        XCTAssertFalse(IptvPlaylistConventions.isWebPage("https://cdn.example.com/x.m3u8?ref=youtube.com"))
        XCTAssertFalse(IptvPlaylistConventions.isWebPage("rtmp://host/live"))
    }

    // MARK: - Directory and defaults

    func testEveryPublicListOfferIsHttpPlaylistForItsSection() {
        for kind in IptvKind.allCases {
            let offers = IptvDirectory.list(kind: kind)
            XCTAssertFalse(offers.isEmpty, "no offer for \(kind)")
            for offer in offers {
                XCTAssertEqual(offer.kind, kind)
                XCTAssertTrue(offer.url.hasPrefix("https://"), offer.url)
                let source = IptvDirectory.toSource(offer, id: "src-test")
                XCTAssertEqual(source.type, .m3u)
                XCTAssertEqual(source.kind, kind)
                XCTAssertEqual(source.url, offer.url)
            }
        }
        XCTAssertTrue(IptvDirectory.list(kind: .tv).contains {
            $0.url == "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
        })
    }

    func testFreshInstallSeedsDefaultForEverySection() {
        let defaults = IptvDirectory.pendingDefaults(seededUrls: [], existingUrls: [])
        XCTAssertEqual(defaults, IptvDirectory.defaults())
        for kind in IptvKind.allCases {
            XCTAssertTrue(defaults.contains { $0.kind == kind }, "no default for \(kind)")
        }
        XCTAssertTrue(defaults.allSatisfy { $0.seeded && $0.url.hasPrefix("https://") })
    }

    func testRemovedDefaultNeverRecreated() {
        let kept = Array(IptvDirectory.defaults().dropFirst())
        let pending = IptvDirectory.pendingDefaults(
            seededUrls: Set(IptvDirectory.defaults().map(\.url)),
            existingUrls: Set(kept.map(\.url))
        )
        XCTAssertTrue(pending.isEmpty)
    }

    func testDefaultIntroducedLaterIsStillSeeded() {
        let old = Array(IptvDirectory.defaults().dropFirst())
        let pending = IptvDirectory.pendingDefaults(
            seededUrls: Set(old.map(\.url)),
            existingUrls: Set(old.map(\.url))
        )
        XCTAssertEqual(pending, [IptvDirectory.defaults().first!])
    }

    func testDefaultAlreadyConfiguredByHandNotAddedTwice() {
        let pending = IptvDirectory.pendingDefaults(
            seededUrls: [],
            existingUrls: Set(IptvDirectory.defaults().map(\.url))
        )
        XCTAssertTrue(pending.isEmpty)
    }

    func testStoreBuildOffersNoTvLists() {
        let store = IptvDirectory.offered(store: true)
        XCTAssertFalse(store.contains { $0.kind == .tv })
        XCTAssertTrue(store.contains { $0.kind == .radio })
        XCTAssertTrue(IptvDirectory.offered(store: false).contains { $0.kind == .tv })
    }
}
