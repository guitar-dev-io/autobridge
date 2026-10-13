import Combine
import UIKit
import WebKit

// The pages of the drawn CarPlay screen. Each is a grid of tiles over the same stores the phone
// and the CarPlay templates read, so a source, a favourite or a playing channel is the same thing
// on every surface.

private var stores: AutoBridgeStores { AutoBridgeStores.shared }

/// A page that is one grid filling the page.
@MainActor
class CarGridPage: CarPage {
    let grid: CarGridView

    init(shape: CarTileButton.Shape) {
        grid = CarGridView(shape: shape)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        grid.frame = view.bounds
        grid.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(grid)
    }
}

// MARK: - Channels

/// The Channels tab: every TV and radio source, as on the phone.
final class CarSourcesPage: CarGridPage {
    private var subscriptions = Set<AnyCancellable>()

    init() {
        super.init(shape: .row)
        title = NSLocalizedString("Channels", comment: "Car screen tab")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        stores.sources.$sources
            .receive(on: RunLoop.main)
            .sink { [weak self] sources in self?.reload(sources) }
            .store(in: &subscriptions)
    }

    private func reload(_ sources: [IptvSource]) {
        let sections = [IptvKind.tv, .radio].map { kind -> CarTileSection in
            let header = kind == .radio
                ? NSLocalizedString("Radio", comment: "Home section")
                : NSLocalizedString("TV", comment: "Home section")
            let tiles = sources.filter { $0.kind == kind }.map(tile(for:))
            return CarTileSection(header: header, tiles: tiles)
        }
        grid.show(sections, emptyText: NSLocalizedString("Add a source on your phone first.", comment: "Car screen"))
    }

    private func tile(for source: IptvSource) -> CarTile {
        let radio = source.kind == .radio
        return CarTile(
            title: source.name,
            subtitle: subtitle(for: source),
            symbol: radio ? "radio" : "tv",
            tint: UIColor(radio ? AutoBridgeDesign.accentRadio : AutoBridgeDesign.accentTV)
        ) { [weak self] in
            self?.screen?.push(CarCategoriesPage(source: source))
        }
    }

    private func subtitle(for source: IptvSource) -> String {
        guard let cached = stores.catalog.cached(source.id) else { return "" }
        return plural(cached.entries.count, "entry", "entries")
    }
}

/// A source's categories; loads the catalog itself when it is not cached yet.
final class CarCategoriesPage: CarGridPage {
    private let source: IptvSource

    init(source: IptvSource) {
        self.source = source
        super.init(shape: .row)
        title = source.name
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        if let cached = stores.catalog.cached(source.id) {
            show(cached)
            return
        }
        grid.show([], emptyText: NSLocalizedString("Loading…", comment: "Loading"))
        Task { [weak self] in
            guard let self else { return }
            switch await stores.catalog.load(self.source) {
            case .ready(let data): self.show(data)
            case .failed(let message): self.grid.show([], emptyText: message)
            }
        }
    }

    private func show(_ data: IptvCatalogData) {
        let source = self.source
        let tiles = data.categories.map { category in
            CarTile(
                title: category.name,
                subtitle: plural(category.count, "entry", "entries"),
                symbol: "folder",
                tint: UIColor(AutoBridgeDesign.accentTV)
            ) { [weak self] in
                self?.screen?.push(CarEntriesPage(
                    source: source,
                    entries: data.entries(in: category.id),
                    title: category.name
                ))
            }
        }
        grid.show([CarTileSection(header: nil, tiles: tiles)])
    }
}

/// A category's channels, as logo tiles. Only what the player can open is listed: series folders
/// and web pages stay on the phone.
final class CarEntriesPage: CarGridPage {
    init(source: IptvSource, entries: [IptvEntry], title: String) {
        super.init(shape: .tile)
        self.title = title
        let playable = entries.filter { !$0.isSeriesFolder && !$0.isWebPage && !$0.url.isEmpty }
        let tracks = playable.map { PlaybackController.Track(entry: $0, source: source) }
        let tiles = playable.enumerated().map { index, entry in
            CarTile(
                title: entry.title,
                logo: entry.logo,
                symbol: source.kind == .radio ? "radio" : "tv",
                tint: UIColor(source.kind == .radio ? AutoBridgeDesign.accentRadio : AutoBridgeDesign.accentTV)
            ) { [weak self] in
                self?.screen?.play(tracks, index: index)
            }
        }
        loadViewIfNeeded()
        grid.show(
            [CarTileSection(header: nil, tiles: tiles)],
            emptyText: NSLocalizedString("Nothing playable here", comment: "CarPlay")
        )
    }
}

// MARK: - Favorites

/// The Favorites tab: starred channels, then the recently played ones, kept in step with the phone.
final class CarFavoritesPage: CarGridPage {
    private var subscriptions = Set<AnyCancellable>()

    init() {
        super.init(shape: .tile)
        title = NSLocalizedString("Favorites", comment: "Car screen tab")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        stores.history.$favorites
            .combineLatest(stores.history.$recent)
            .receive(on: RunLoop.main)
            .sink { [weak self] favorites, recent in self?.reload(favorites, recent) }
            .store(in: &subscriptions)
    }

    private func reload(_ favorites: [IptvHistoryItem], _ recent: [IptvHistoryItem]) {
        grid.show(
            [
                section(NSLocalizedString("Favorites", comment: "Car screen tab"), favorites),
                section(
                    NSLocalizedString("Recently played", comment: "Row"),
                    Array(recent.filter { $0.playback == .stream }.prefix(30))
                ),
            ],
            emptyText: NSLocalizedString("Star a channel on your phone.", comment: "CarPlay")
        )
    }

    private func section(_ header: String, _ items: [IptvHistoryItem]) -> CarTileSection {
        let tracks = items.map(PlaybackController.Track.init(item:))
        return CarTileSection(header: header, tiles: items.enumerated().map { index, item in
            CarTile(
                title: item.title,
                logo: item.logo,
                symbol: item.kind == .radio ? "radio" : "tv",
                tint: UIColor(item.kind == .radio ? AutoBridgeDesign.accentRadio : AutoBridgeDesign.accentTV)
            ) { [weak self] in
                self?.screen?.play(tracks, index: index)
            }
        })
    }
}

// MARK: - Quick

/// The Quick tab: what is playing, and the browser (left out of the App Store build).
final class CarQuickPage: CarGridPage {
    private var subscriptions = Set<AnyCancellable>()

    init() {
        super.init(shape: .row)
        title = NSLocalizedString("Quick", comment: "Car screen tab")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        stores.playback.$current
            .map { $0?.title }
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] playing in self?.reload(playing: playing) }
            .store(in: &subscriptions)
    }

    private func reload(playing: String?) {
        var tiles: [CarTile] = []
        if let playing {
            tiles.append(CarTile(
                title: NSLocalizedString("Now playing", comment: "Car screen"),
                subtitle: playing,
                symbol: "play.rectangle.fill",
                tint: UIColor(AutoBridgeDesign.accentVideo)
            ) { [weak self] in self?.screen?.showPlayer() })
        }
        if !BuildFlavor.isStore {
            tiles.append(CarTile(
                title: NSLocalizedString("Browser", comment: "Home section"),
                subtitle: NSLocalizedString("Web pages and video sites", comment: "Car screen"),
                symbol: "safari.fill",
                tint: UIColor(AutoBridgeDesign.accentWeb)
            ) { [weak self] in self?.screen?.openBrowser() })
        }
        tiles.append(CarTile(
            title: NSLocalizedString("Channels", comment: "Car screen tab"),
            subtitle: NSLocalizedString("TV and radio sources", comment: "Car screen"),
            symbol: "tv.fill",
            tint: UIColor(AutoBridgeDesign.accentTV)
        ) { [weak self] in self?.screen?.select(.channels) })
        grid.show([CarTileSection(header: nil, tiles: tiles)])
    }
}

// MARK: - Browser

/// The browser on the car screen: a toolbar (close, back, home, address, play, full screen, reload,
/// follow the phone) over either a grid of video sites or the page itself. The page is the shared
/// `CarWebScreen` web view, so the phone's "send to car" lands here too.
final class CarBrowserPage: CarPage {
    private let toolbar = UIStackView()
    private let address = UIButton(type: .custom)
    private var followButton: UIButton!
    private let home = CarGridView(shape: .tile)
    private let web = CarWebViewController()
    private var showingHome = true
    private var pageTop: NSLayoutConstraint?
    private lazy var exitFullscreen = carIconButton("arrow.down.right.and.arrow.up.left", size: 40) { [weak self] in
        self?.screen?.setFullscreen(false)
    }
    private var subscriptions = Set<AnyCancellable>()
    private var screenWeb: CarWebScreen { CarWebScreen.shared }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = NSLocalizedString("Browser", comment: "Home section")
        view.backgroundColor = CarStyle.background

        address.backgroundColor = CarStyle.surface
        address.layer.cornerRadius = 20
        address.titleLabel?.font = CarStyle.font(15)
        address.titleLabel?.lineBreakMode = .byTruncatingMiddle
        address.contentHorizontalAlignment = .leading
        address.contentEdgeInsets = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
        address.heightAnchor.constraint(equalToConstant: 40).isActive = true
        address.setContentHuggingPriority(.defaultLow, for: .horizontal)
        address.addAction(UIAction { [weak self] _ in self?.screen?.onAddress?() }, for: .touchUpInside)

        followButton = carIconButton("link", size: 40) { [weak self] in
            guard let self else { return }
            self.screenWeb.followsPhone.toggle()
        }
        for button in [
            carIconButton("xmark", size: 40) { [weak self] in self?.screen?.back() },
            carIconButton("chevron.left", size: 40) { [weak self] in self?.webBack() },
            carIconButton("house.fill", size: 40) { [weak self] in self?.setHome(true) },
        ] { toolbar.addArrangedSubview(button) }
        toolbar.addArrangedSubview(address)
        for button in [
            carIconButton("play.fill", size: 40) { [weak self] in self?.playPageVideo() },
            carIconButton("arrow.up.left.and.arrow.down.right", size: 40) { [weak self] in
                self?.screen?.setFullscreen(true)
            },
            carIconButton("arrow.clockwise", size: 40) { CarWebScreen.shared.reload() },
            followButton!,
        ] { toolbar.addArrangedSubview(button) }
        toolbar.axis = .horizontal
        toolbar.alignment = .center
        toolbar.spacing = 8
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(toolbar)

        addChild(web)
        web.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web.view)
        web.didMove(toParent: self)
        home.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(home)

        NSLayoutConstraint.activate([
            toolbar.topAnchor.constraint(equalTo: view.topAnchor),
            toolbar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            toolbar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            web.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            web.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            home.topAnchor.constraint(equalTo: web.view.topAnchor),
            home.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            home.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            home.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        pageTop = web.view.topAnchor.constraint(equalTo: toolbar.bottomAnchor, constant: 8)
        pageTop?.isActive = true

        // The way out of full screen by touch; the car's back button does the same.
        exitFullscreen.alpha = 0.7
        exitFullscreen.isHidden = true
        view.addSubview(exitFullscreen)
        NSLayoutConstraint.activate([
            exitFullscreen.topAnchor.constraint(equalTo: view.topAnchor, constant: 8),
            exitFullscreen.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
        ])

        home.show(StreamingLinks.grouped().map { group in
            CarTileSection(header: group.group.title, tiles: group.links.map { link in
                CarTile(
                    title: link.title,
                    subtitle: link.url.host ?? "",
                    symbol: Self.symbol(for: link.group),
                    tint: UIColor(AutoBridgeDesign.accentWeb)
                ) { [weak self] in self?.open(link.url) }
            })
        })

        screenWeb.attach(web, as: .carPlay)
        screenWeb.$currentURL
            .combineLatest(screenWeb.$pageTitle)
            .receive(on: RunLoop.main)
            .sink { [weak self] url, pageTitle in self?.updateAddress(url: url, title: pageTitle) }
            .store(in: &subscriptions)
        // A page that arrives from elsewhere (the phone, CarPlay's keyboard) is shown, not the grid.
        screenWeb.$currentURL
            .dropFirst()
            .removeDuplicates()
            .filter { !$0.isEmpty }
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.setHome(false) }
            .store(in: &subscriptions)
        screenWeb.$followsPhone
            .receive(on: RunLoop.main)
            .sink { [weak self] on in
                self?.followButton.tintColor = on ? CarStyle.accent : CarStyle.text
            }
            .store(in: &subscriptions)
        setHome(screenWeb.currentURL.isEmpty)
    }

    private static func symbol(for group: StreamingGroup) -> String {
        switch group {
        case .video: return "play.rectangle.fill"
        case .music: return "music.note"
        case .live: return "dot.radiowaves.left.and.right"
        case .anime: return "sparkles.tv.fill"
        }
    }

    private func open(_ url: URL) {
        screenWeb.show(url)
        setHome(false)
    }

    private func setHome(_ on: Bool) {
        showingHome = on
        home.isHidden = !on
        web.view.isHidden = on
    }

    private func webBack() {
        if showingHome, !screenWeb.currentURL.isEmpty {
            setHome(false)
        } else {
            screenWeb.goBack()
        }
    }

    private func updateAddress(url: String, title: String) {
        let text = title.isEmpty ? (URL(string: url)?.host ?? url) : title
        if text.isEmpty {
            address.setTitle(NSLocalizedString("Enter a URL or search", comment: "Car screen"), for: .normal)
            address.setTitleColor(CarStyle.secondary, for: .normal)
        } else {
            address.setTitle(text, for: .normal)
            address.setTitleColor(CarStyle.text, for: .normal)
        }
    }

    /// Starts the page's first video, for sites that wait for a tap the car screen cannot give.
    private func playPageVideo() {
        screenWeb.webView.evaluateJavaScript(
            "(function(){var v=document.querySelector('video');if(v){v.muted=false;v.play();return true}return false})()"
        )
    }

    /// Full screen leaves only the page.
    func setChromeHidden(_ hidden: Bool) {
        guard isViewLoaded else { return }
        toolbar.isHidden = hidden
        exitFullscreen.isHidden = !hidden
        pageTop?.isActive = false
        pageTop = hidden
            ? web.view.topAnchor.constraint(equalTo: view.topAnchor)
            : web.view.topAnchor.constraint(equalTo: toolbar.bottomAnchor, constant: 8)
        pageTop?.isActive = true
        if hidden { setHome(false) }
    }

    /// Up and down scroll the page while one is showing; the toolbar is reached with left/right.
    override func handleMove(_ direction: CarDirection) -> Bool {
        guard !showingHome else { return false }
        switch direction {
        case .up: screenWeb.scroll(pages: -0.8)
        case .down: screenWeb.scroll(pages: 0.8)
        default: return false
        }
        return true
    }
}
