import AVFoundation
import CarPlay
import Combine
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
    /// The CarPlay window, when the app holds the navigation entitlement; see the web section below.
    private var carWebWindow: CPWindow?
    /// Where the last pan update left off, so each update scrolls by the change since then.
    private var lastPan: CGPoint = .zero
    /// Keeps the Favorites and Recently-played tabs in step with the phone while connected.
    private var subscriptions = Set<AnyCancellable>()

    private var stores: AutoBridgeStores { AutoBridgeStores.shared }

    /// How many rows one pushed page holds. CarPlay refuses a template above its own limit, so a
    /// long category is paged with a "More" row rather than truncated silently.
    private var pageSize: Int { min(CPListTemplate.maximumItemCount, 100) }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        subscriptions.removeAll()
        interfaceController.setRootTemplate(rootTemplate(), animated: true, completion: nil)
        addCloseButton()
        VehicleReminders.carConnected()
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
        subscriptions.removeAll()
        VehicleReminders.carDisconnected()
    }

    // MARK: - Root

    /// The dashboard, laid out like the Android car home: a Home grid with the quick-access tiles in
    /// the same order, then the Favorites and Recently-played lists. Every tab carries both a title
    /// and an image; a tab without an image is what CarPlay draws as an unlabelled "More".
    private func rootTemplate() -> CPTemplate {
        let tabs: [CPTemplate] = [homeTemplate(), favoritesTemplate(), recentTemplate()]
        return CPTabBarTemplate(templates: Array(tabs.prefix(CPTabBarTemplate.maximumTabCount)))
    }

    private func homeTemplate() -> CPGridTemplate {
        let scale = interfaceController?.carTraitCollection.displayScale ?? 2
        let buttons = HomeSection.carQuickAccess.prefix(8).map { section in
            CPGridButton(
                titleVariants: [section.plainTitle],
                image: CarPlayHome.tileImage(for: section, scale: scale)
            ) { [weak self] _ in
                self?.open(section)
            }
        }
        let template = CPGridTemplate(title: "AutoBridge", gridButtons: Array(buttons))
        template.tabTitle = NSLocalizedString("Home", comment: "CarPlay tab")
        template.tabImage = UIImage(systemName: "square.grid.2x2.fill")
        return template
    }

    /// A home tile. TV and Radio drill into their sources; the browser-backed tiles open a list of
    /// what can play in the car, since a web page cannot be shown there.
    private func open(_ section: HomeSection) {
        let template: CPListTemplate
        switch section {
        case .tv, .radio:
            template = sourcesTemplate(kind: section.iptvKind ?? .tv, title: section.plainTitle)
        default:
            template = hubTemplate(section)
        }
        interfaceController?.pushTemplate(template, animated: true, completion: nil)
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

    /// YouTube, YouTube Music, the browser and the streaming sites are web pages, which the car
    /// cannot show. Their tile still plays: a now-playing row, the remembered channels that are real
    /// streams, and a note saying the site itself opens on the phone.
    private func hubTemplate(_ section: HomeSection) -> CPListTemplate {
        var sections: [CPListSection] = []

        if let current = stores.playback.current {
            let row = CPListItem(
                text: String(
                    format: NSLocalizedString("Now playing: %@", comment: "CarPlay"),
                    current.title
                ),
                detailText: nil,
                image: UIImage(systemName: "speaker.wave.2.fill"),
                accessoryImage: nil,
                accessoryType: .disclosureIndicator
            )
            row.handler = { [weak self] _, completion in
                self?.showNowPlaying()
                completion()
            }
            sections.append(CPListSection(items: [row]))
        }

        let playable = CarPlayHome.playable(
            for: section,
            favorites: stores.history.favorites,
            recent: stores.history.recent
        )
        let limit = max(CPListTemplate.maximumItemCount - 2, 1)
        let tracks = playable.map(PlaybackController.Track.init(item:))
        let rows = playable.prefix(limit).enumerated().map { index, entry -> CPListItem in
            let item = CPListItem(text: entry.title, detailText: kindLabel(entry.kind))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: index)
                completion()
            }
            load(entry.logo, into: item)
            return item
        }
        if !rows.isEmpty {
            sections.append(CPListSection(
                items: rows,
                header: NSLocalizedString("Play in the car", comment: "CarPlay"),
                sectionIndexTitle: nil
            ))
        }

        let note = CarPlayHome.phoneNote(for: section)
        if let note {
            let row = CPListItem(
                text: note.title,
                detailText: note.subtitle,
                image: UIImage(systemName: "iphone"),
                accessoryImage: nil,
                accessoryType: .none
            )
            row.handler = { [weak self] _, completion in
                self?.presentNote(note.title, note.subtitle)
                completion()
            }
            sections.append(CPListSection(items: [row]))
        }

        let template = CPListTemplate(title: section.plainTitle, sections: sections)
        if let note {
            template.emptyViewTitleVariants = [note.title]
            template.emptyViewSubtitleVariants = [note.subtitle]
        }
        return template
    }

    private func presentNote(_ title: String, _ subtitle: String) {
        let ok = CPAlertAction(
            title: NSLocalizedString("OK", comment: "Button"),
            style: .default
        ) { [weak self] _ in
            self?.interfaceController?.dismissTemplate(animated: true, completion: nil)
        }
        let alert = CPAlertTemplate(titleVariants: [title + "\n" + subtitle, title], actions: [ok])
        interfaceController?.presentTemplate(alert, animated: true, completion: nil)
    }

    private func favoritesTemplate() -> CPListTemplate {
        let title = HomeSection.favorites.plainTitle
        let template = CPListTemplate(
            title: title,
            sections: favoritesSections(stores.history.favorites)
        )
        template.tabTitle = title
        template.tabImage = UIImage(systemName: "star.fill")
        template.emptyViewTitleVariants = [
            NSLocalizedString("Nothing saved yet", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            NSLocalizedString("Star a channel on your phone.", comment: "CarPlay")
        ]
        // Stays live: a channel starred on the phone (or from Now Playing) shows up without a reconnect.
        stores.history.$favorites
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak template] favorites in
                Task { @MainActor in
                    guard let self, let template else { return }
                    template.updateSections(self.favoritesSections(favorites))
                }
            }
            .store(in: &subscriptions)
        return template
    }

    private func favoritesSections(_ favorites: [IptvHistoryItem]) -> [CPListSection] {
        let shown = Array(favorites.prefix(CPListTemplate.maximumItemCount))
        let tracks = favorites.map(PlaybackController.Track.init(item:))
        let items = shown.enumerated().map { index, favorite -> CPListItem in
            let item = CPListItem(text: favorite.title, detailText: kindLabel(favorite.kind))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: index)
                completion()
            }
            load(favorite.logo, into: item)
            return item
        }
        return [CPListSection(items: items)]
    }

    private func recentTemplate() -> CPListTemplate {
        let title = NSLocalizedString("Recently played", comment: "Row")
        let template = CPListTemplate(
            title: title,
            sections: recentSections(stores.history.recent)
        )
        template.tabTitle = title
        template.tabImage = UIImage(systemName: "clock.fill")
        template.emptyViewTitleVariants = [
            NSLocalizedString("Nothing yet", comment: "CarPlay")
        ]
        template.emptyViewSubtitleVariants = [
            NSLocalizedString("Channels you play show up here.", comment: "CarPlay")
        ]
        stores.history.$recent
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak template] recent in
                Task { @MainActor in
                    guard let self, let template else { return }
                    template.updateSections(self.recentSections(recent))
                }
            }
            .store(in: &subscriptions)
        return template
    }

    private func recentSections(_ all: [IptvHistoryItem]) -> [CPListSection] {
        let recent = all.filter { $0.playback == .stream }
        let shown = Array(recent.prefix(CPListTemplate.maximumItemCount))
        let tracks = recent.map(PlaybackController.Track.init(item:))
        let items = shown.enumerated().map { index, entry -> CPListItem in
            let item = CPListItem(text: entry.title, detailText: kindLabel(entry.kind))
            item.handler = { [weak self] _, completion in
                self?.play(tracks, index: index)
                completion()
            }
            load(entry.logo, into: item)
            return item
        }
        return [CPListSection(items: items)]
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
        // Later pages replace the current one rather than stacking on it: CarPlay allows five levels,
        // and Home → sources → categories → entries → Now Playing already uses them all. Back still
        // returns to the category list.
        if page > 0 {
            replaceTop(with: template)
        } else {
            interfaceController?.pushTemplate(template, animated: true, completion: nil)
        }

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

    /// A close button on the Now Playing screen: stops and unloads the channel, then goes back to
    /// the list it was picked from. Without it a TV channel could only be paused from the car.
    private func addCloseButton() {
        guard let image = UIImage(systemName: "xmark.circle") else { return }
        let close = CPNowPlayingImageButton(image: image) { [weak self] _ in
            guard let self else { return }
            self.stores.playback.stop()
            if self.interfaceController?.topTemplate === CPNowPlayingTemplate.shared {
                self.interfaceController?.popTemplate(animated: true, completion: nil)
            }
        }
        CPNowPlayingTemplate.shared.updateNowPlayingButtons([close])
    }

    private func play(_ tracks: [PlaybackController.Track], index: Int) {
        guard tracks.indices.contains(index) else { return }
        let track = tracks[index]
        stores.playback.play(track, queue: tracks, index: index)
        if let origin = track.origin {
            stores.history.recordPlayback(origin)
        }
        showNowPlaying()
    }

    /// Pushes Now Playing unless it is already on the stack; CarPlay throws on a second push of the
    /// same template.
    private func showNowPlaying() {
        guard let controller = interfaceController else { return }
        let shared = CPNowPlayingTemplate.shared
        if controller.templates.contains(where: { $0 === shared }) {
            if controller.topTemplate !== shared {
                controller.pop(to: shared, animated: true, completion: nil)
            }
            return
        }
        controller.pushTemplate(shared, animated: true, completion: nil)
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

// MARK: - Web pages on the CarPlay screen

/// A real web page on the CarPlay screen. CarPlay hands an app a window it can draw in only when
/// the app holds the navigation entitlement (`com.apple.developer.carplay-maps`), and then calls the
/// `to window:` variant below instead of the audio one above. Apple grants that entitlement to
/// navigation apps only, so only the Simulator's entitlements declare it: on a device the app stays
/// the audio surface and none of this runs.
///
/// The window takes no touches, so the page is driven the way a map is: pan to scroll, map buttons
/// for up/down/back/reload, a search button for the address (CarPlay's own keyboard), and the
/// phone browser's car controls. The audio home is one button away.
extension CarPlaySceneDelegate: CPMapTemplateDelegate, CPSearchTemplateDelegate {
    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController,
        to window: CPWindow
    ) {
        self.interfaceController = interfaceController
        subscriptions.removeAll()
        let controller = CarWebViewController()
        window.rootViewController = controller
        carWebWindow = window
        let screen = CarWebScreen.shared
        screen.attach(controller, as: .carPlay)
        if screen.currentURL.isEmpty, let start = URL(string: "https://www.google.com") {
            screen.show(start)
        }
        interfaceController.setRootTemplate(webMapTemplate(), animated: true, completion: nil)
        addCloseButton()
        VehicleReminders.carConnected()
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController,
        from window: CPWindow
    ) {
        CarWebScreen.shared.detach(.carPlay)
        carWebWindow = nil
        self.interfaceController = nil
        subscriptions.removeAll()
        VehicleReminders.carDisconnected()
    }

    private func webMapTemplate() -> CPMapTemplate {
        let map = CPMapTemplate()
        map.mapDelegate = self
        map.automaticallyHidesNavigationBar = false
        let screen = CarWebScreen.shared
        map.mapButtons = [
            mapButton("chevron.up") { screen.scroll(pages: -0.8) },
            mapButton("chevron.down") { screen.scroll(pages: 0.8) },
            mapButton("arrow.uturn.backward") { screen.goBack() },
            mapButton("arrow.clockwise") { screen.reload() },
        ]
        let home = CPBarButton(image: UIImage(systemName: "square.grid.2x2") ?? UIImage()) { [weak self] _ in
            guard let self else { return }
            self.interfaceController?.pushTemplate(self.homeTemplate(), animated: true, completion: nil)
        }
        let address = CPBarButton(image: UIImage(systemName: "magnifyingglass") ?? UIImage()) { [weak self] _ in
            self?.openAddressSearch()
        }
        map.leadingNavigationBarButtons = [home]
        map.trailingNavigationBarButtons = [address]
        return map
    }

    private func mapButton(_ symbol: String, action: @escaping () -> Void) -> CPMapButton {
        let button = CPMapButton { _ in action() }
        button.image = UIImage(systemName: symbol)
        return button
    }

    private func openAddressSearch() {
        let search = CPSearchTemplate()
        search.delegate = self
        interfaceController?.pushTemplate(search, animated: true, completion: nil)
    }

    // MARK: Pan to scroll

    func mapTemplateDidBeginPanGesture(_ mapTemplate: CPMapTemplate) {
        lastPan = .zero
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didUpdatePanGestureWithTranslation translation: CGPoint, velocity: CGPoint) {
        // A finger moving down drags the page down, which moves the view up the page.
        let delta = CGPoint(x: lastPan.x - translation.x, y: lastPan.y - translation.y)
        lastPan = translation
        CarWebScreen.shared.scroll(by: delta)
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didEndPanGestureWithVelocity velocity: CGPoint) {
        lastPan = .zero
    }

    /// The pan buttons a car without a touch screen shows.
    func mapTemplate(_ mapTemplate: CPMapTemplate, panWith direction: CPMapTemplate.PanDirection) {
        let step: CGFloat = 240
        var delta = CGPoint.zero
        if direction.contains(.up) { delta.y -= step }
        if direction.contains(.down) { delta.y += step }
        if direction.contains(.left) { delta.x -= step }
        if direction.contains(.right) { delta.x += step }
        CarWebScreen.shared.scroll(by: delta)
    }

    // MARK: Address search

    func searchTemplate(
        _ searchTemplate: CPSearchTemplate,
        updatedSearchText searchText: String,
        completionHandler: @escaping ([CPListItem]) -> Void
    ) {
        completionHandler(webSuggestions(for: searchText))
    }

    func searchTemplate(
        _ searchTemplate: CPSearchTemplate,
        selectedResult item: CPListItem,
        completionHandler: @escaping () -> Void
    ) {
        if let address = item.userInfo as? String, let url = URL(string: address) {
            CarWebScreen.shared.show(url)
        }
        interfaceController?.popToRootTemplate(animated: true, completion: nil)
        completionHandler()
    }

    /// What was typed (as an address or a search), then the saved pages that match it.
    private func webSuggestions(for text: String) -> [CPListItem] {
        var items: [CPListItem] = []
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = WebViewModel.resolve(trimmed) {
            let item = CPListItem(text: trimmed, detailText: url.host ?? url.absoluteString)
            item.userInfo = url.absoluteString
            items.append(item)
        }
        let needle = trimmed.lowercased()
        let saved = stores.bookmarks.bookmarks.filter { bookmark in
            needle.isEmpty
                || bookmark.title.lowercased().contains(needle)
                || bookmark.url.lowercased().contains(needle)
        }
        for bookmark in saved.prefix(12) {
            let item = CPListItem(text: bookmark.title, detailText: URL(string: bookmark.url)?.host ?? bookmark.url)
            item.userInfo = bookmark.url
            items.append(item)
        }
        return items
    }
}
