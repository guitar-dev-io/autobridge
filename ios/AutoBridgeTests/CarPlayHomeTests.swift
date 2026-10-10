import UIKit
import XCTest
@testable import AutoBridge

final class CarPlayHomeTests: XCTestCase {
    private func item(
        _ url: String,
        kind: IptvKind,
        playback: IptvPlayback = .stream,
        title: String = "Channel"
    ) -> IptvHistoryItem {
        IptvHistoryItem(
            sourceId: "s",
            title: title,
            url: url,
            type: .live,
            kind: kind,
            playback: playback
        )
    }

    func testQuickAccessMatchesAndroidOrderAndFitsTheGrid() {
        XCTAssertEqual(
            HomeSection.carQuickAccess,
            [.tv, .radio, .web, .youtube, .youtubeMusic, .streaming]
        )
        XCTAssertLessThanOrEqual(HomeSection.carQuickAccess.count, 8)
    }

    func testEveryTileSymbolResolvesAndImageIsSixtyPoints() {
        for section in HomeSection.carQuickAccess {
            XCTAssertNotNil(UIImage(systemName: section.carSymbol), section.carSymbol)
            let image = CarPlayHome.tileImage(for: section, scale: 2)
            XCTAssertEqual(image.size, CGSize(width: 60, height: 60), section.rawValue)
            XCTAssertEqual(image.scale, 2)
        }
    }

    func testPlayableKeepsStreamsOnlyDeduplicatedFavoritesFirst() {
        let favorites = [
            item("http://a", kind: .tv, title: "fav-a"),
            item("http://web", kind: .tv, playback: .webPage),
            item("", kind: .radio)
        ]
        let recent = [
            item("http://a", kind: .tv, title: "recent-a"),
            item("http://b", kind: .radio)
        ]
        let result = CarPlayHome.playable(for: .streaming, favorites: favorites, recent: recent)
        XCTAssertEqual(result.map(\.url), ["http://a", "http://b"])
        XCTAssertEqual(result.first?.title, "fav-a")
    }

    func testPlayableFiltersByKindPerTile() {
        let favorites = [item("http://tv", kind: .tv), item("http://radio", kind: .radio)]
        XCTAssertEqual(
            CarPlayHome.playable(for: .youtube, favorites: favorites, recent: []).map(\.url),
            ["http://tv"]
        )
        XCTAssertEqual(
            CarPlayHome.playable(for: .youtubeMusic, favorites: favorites, recent: []).map(\.url),
            ["http://radio"]
        )
        XCTAssertEqual(
            CarPlayHome.playable(for: .web, favorites: favorites, recent: []).count,
            2
        )
        XCTAssertEqual(
            CarPlayHome.playable(for: .streaming, favorites: favorites, recent: []).count,
            2
        )
    }

    func testPhoneNoteOnlyForBrowserBackedTiles() {
        for section in HomeSection.allCases {
            let expected: Set<HomeSection> = [.web, .youtube, .youtubeMusic, .streaming]
            XCTAssertEqual(
                CarPlayHome.phoneNote(for: section) != nil,
                expected.contains(section),
                section.rawValue
            )
        }
    }
}
