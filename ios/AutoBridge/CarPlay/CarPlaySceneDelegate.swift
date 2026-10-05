import AVFoundation
import CarPlay
import Foundation
import UIKit

/// The CarPlay audio surface.
///
/// This is the counterpart of the Android Auto dashboard, as far as CarPlay allows one: the same
/// sections, the same sources, the same catalogs out of the same cache, the same favourites and
/// recently-played lists, and the same `PlaybackController` — so a channel started here shows up in
/// the phone's now-playing bar and the other way round.
///
/// It is audio-only, and that is a platform boundary rather than a decision: CarPlay is template
/// based and has no mirroring category and no arbitrary video surface, so a TV channel plays its
/// audio here and its picture stays on the phone. Running on real hardware needs the
/// `com.apple.developer.carplay-audio` entitlement, which Apple grants on request; without it this
/// scene is simply never created and the phone app is unaffected.
@MainActor
@objc(CarPlaySceneDelegate)
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?

    private var stores: AutoBridgeStores { AutoBridgeStores.shared }

    /// How many rows one pushed page holds. CarPlay refuses a template above its own limit, so a
    /// long category is paged with a "More" row rather than truncated silently.
    private var pageSize: Int { min(CPListTemplate.maximumItemCount, 100) }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        interfaceController.setRootTemplate(rootTemplate(), animated: true, completion: nil)
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
    }

    // MARK: - Root

    /// The dashboard: the sections CarPlay can serve, in the shared `HomeSection` order.
    private func rootTemplate() -> CPTemplate {
        let tabs: [CPListTemplate] = HomeSection.carSections.map { section in
            switch section {
            case .radio:
                return sourcesTemplate(kind: .radio, title: section.plainTitle)
            case .tv:
                return sourcesTemplate(kind: .tv, title: section.plainTitle)
            default:
                return favoritesTemplate(title: section.plainTitle)
            }
        } + [recentTemplate()]
        let bar = CPTabBarTemplate(templates: tabs)
        return bar
    }

    private func sourcesTemplate(kind: IptvKind, title: String) -> CPListTemplate {
        let sources = stores.sources.sources(kind: kind)
        let items = sources.map { source -> CPListItem in
            let item = CPListItem(
                text: source.name,
                detailText: detail(for: source),
                image: nil,
                accessoryImage: nil,
                accessoryType: .disclosureIndicator
            )
            item.handler = { [weak self] _, completion in
                self?.openSource(source)
                completion()
            }
            return item
        }
        let template = CPListTemplate(
            title: title,
            sections: [CPListSection(items: items)]
        )
        template.tabTitle = title
        template.emptyViewTitleVariants = [
            NSLocalizedString("No sources", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            String(
                format: NSLocalizedString("Add a %@ source on your phone first.", comment: "CarPlay"),
                title
            )
        ]
        return template
    }

    private func favoritesTemplate(title: String) -> CPListTemplate {
        let favorites = stores.history.favorites
        let tracks = favorites.map(PlaybackController.Track.init(item:))
        let items = favorites.enumerated().map { index, favorite -> CPListItem in
            let item = CPListItem(text: favorite.title, detailText: kindLabel(favorite.kind))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: index)
                completion()
            }
            load(favorite.logo, into: item)
            return item
        }
        let template = CPListTemplate(title: title, sections: [CPListSection(items: items)])
        template.tabTitle = title
        template.emptyViewTitleVariants = [
            NSLocalizedString("Nothing saved yet", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            NSLocalizedString("Star a channel on your phone.", comment: "CarPlay")
        ]
        return template
    }

    private func recentTemplate() -> CPListTemplate {
        let recent = stores.history.recent.filter { $0.playback == .stream }
        let tracks = recent.map(PlaybackController.Track.init(item:))
        let items = recent.enumerated().map { index, entry -> CPListItem in
            let item = CPListItem(text: entry.title, detailText: kindLabel(entry.kind))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: index)
                completion()
            }
            load(entry.logo, into: item)
            return item
        }
        let title = NSLocalizedString("Recently played", comment: "Row")
        let template = CPListTemplate(title: title, sections: [CPListSection(items: items)])
        template.tabTitle = title
        template.emptyViewTitleVariants = [
            NSLocalizedString("Nothing yet", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            NSLocalizedString("Channels you play show up here.", comment: "CarPlay")
        ]
        return template
    }

    // MARK: - Drill-down

    private func openSource(_ source: IptvSource) {
        if let cached = stores.catalog.cached(source.id) {
            pushCategories(source, cached)
            return
        }
        let loading = CPListTemplate(
            title: source.name,
            sections: [
                CPListSection(items: [
                    CPListItem(
                        text: NSLocalizedString("Loading…", comment: "Loading"),
                        detailText: nil
                    )
                ])
            ]
        )
        interfaceController?.pushTemplate(loading, animated: true, completion: nil)
        Task { [weak self] in
            guard let self else { return }
            switch await self.stores.catalog.load(source) {
            case .ready(let data):
                self.replaceTop(with: self.categoriesTemplate(source, data))
            case .failed(let message):
                self.replaceTop(
                    with: self.messageTemplate(title: source.name, message: message)
                )
            }
        }
    }

    private func pushCategories(_ source: IptvSource, _ data: IptvCatalogData) {
        interfaceController?.pushTemplate(
            categoriesTemplate(source, data),
            animated: true,
            completion: nil
        )
    }

    private func categoriesTemplate(
        _ source: IptvSource,
        _ data: IptvCatalogData
    ) -> CPListTemplate {
        let items = data.categories.map { category -> CPListItem in
            let item = CPListItem(
                text: category.name,
                detailText: plural(category.count, "entry", "entries"),
                image: nil,
                accessoryImage: nil,
                accessoryType: .disclosureIndicator
            )
            item.handler = { [weak self] _, completion in
                guard let self else {
                    completion()
                    return
                }
                self.pushEntries(
                    source: source,
                    entries: data.entries(in: category.id),
                    title: category.name,
                    page: 0
                )
                completion()
            }
            return item
        }
        return CPListTemplate(title: source.name, sections: [CPListSection(items: items)])
    }

    /// One page of a category. CarPlay has a hard cap per template, so the rest of a long list sits
    /// behind a "More" row instead of being dropped.
    private func pushEntries(source: IptvSource, entries: [IptvEntry], title: String, page: Int) {
        let playable = entries.filter { !$0.isSeriesFolder && !$0.isWebPage && !$0.url.isEmpty }
        let tracks = playable.map { PlaybackController.Track(entry: $0, source: source) }
        let start = page * pageSize
        let slice = Array(playable.dropFirst(start).prefix(pageSize))

        var items: [CPListItem] = slice.enumerated().map { offset, entry in
            let item = CPListItem(text: entry.title, detailText: detail(for: entry))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: start + offset)
                completion()
            }
            load(entry.logo, into: item)
            return item
        }
        if start + slice.count < playable.count {
            let more = CPListItem(
                text: NSLocalizedString("More", comment: "CarPlay"),
                detailText: plural(playable.count - start - slice.count, "entry", "entries"),
                image: nil,
                accessoryImage: nil,
                accessoryType: .disclosureIndicator
            )
            more.handler = { [weak self] _, completion in
                self?.pushEntries(
                    source: source,
                    entries: entries,
                    title: title,
                    page: page + 1
                )
                completion()
            }
            items.append(more)
        }

        let template = CPListTemplate(title: title, sections: [CPListSection(items: items)])
        template.emptyViewTitleVariants = [
            NSLocalizedString("Nothing playable here", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            NSLocalizedString(
                "This category holds only folders or web pages, which the car cannot open.",
                comment: "CarPlay"
            )
        ]
        interfaceController?.pushTemplate(template, animated: true, completion: nil)

        // The same automatic check the phone page runs, so a dead channel reads as dead here too.
        let urls = slice.map(\.url)
        StreamPing.checkAll(Array(urls.prefix(24))) { [weak self] progress in
            guard let self, progress.done else { return }
            for (index, entry) in slice.enumerated() {
                guard index < items.count, let result = StreamPing.cached(entry.url) else { continue }
                items[index].setDetailText(
                    [self.detail(for: entry), StreamPing.describe(result)]
                        .compactMap { $0 }
                        .joined(separator: " • ")
                )
            }
        }
    }

    private func messageTemplate(title: String, message: String) -> CPListTemplate {
        CPListTemplate(
            title: title,
            sections: [CPListSection(items: [CPListItem(text: message, detailText: nil)])]
        )
    }

    private func replaceTop(with template: CPListTemplate) {
        guard let controller = interfaceController else { return }
        controller.popTemplate(animated: false) { _, _ in
            controller.pushTemplate(template, animated: false, completion: nil)
        }
    }

    // MARK: - Playback

    private func play(_ tracks: [PlaybackController.Track], index: Int) {
        guard tracks.indices.contains(index) else { return }
        let track = tracks[index]
        stores.playback.play(track, queue: tracks, index: index)
        if let origin = track.origin {
            stores.history.recordPlayback(origin)
        }
        interfaceController?.pushTemplate(
            CPNowPlayingTemplate.shared,
            animated: true,
            completion: nil
        )
    }

    // MARK: - Row text and artwork

    private func detail(for source: IptvSource) -> String {
        let type = source.type == .xtream
            ? NSLocalizedString("Xtream", comment: "Source type")
            : NSLocalizedString("M3U playlist", comment: "Source type")
        if let cached = stores.catalog.cached(source.id) {
            return type + " • " + plural(cached.entries.count, "entry", "entries")
        }
        return type
    }

    private func detail(for entry: IptvEntry) -> String? {
        let parts = [
            entry.subtitle.isEmpty ? nil : entry.subtitle,
            StreamPing.cached(entry.url).map(StreamPing.describe)
        ].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    private func kindLabel(_ kind: IptvKind) -> String {
        kind == .radio
            ? NSLocalizedString("Radio", comment: "Home section")
            : NSLocalizedString("TV", comment: "Home section")
    }

    /// Channel logos on the head unit, from the same cache the phone rows read.
    private func load(_ url: String, into item: CPListItem) {
        guard !url.isEmpty else { return }
        if let ready = ImageLoader.shared.cached(url) {
            item.setImage(ready)
            return
        }
        Task {
            guard let image = await ImageLoader.shared.load(url) else { return }
            item.setImage(image)
        }
    }
}
