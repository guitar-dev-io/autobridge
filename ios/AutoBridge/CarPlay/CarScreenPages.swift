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

// MARK: - Home

/// The car home, as on Android Auto: a Now Playing card while something plays, the six
/// quick-access cards (TV, Radio, Web browser, YouTube, YouTube Music, Streaming), and the channels
/// played lately, three to a line.
final class CarHomePage: CarPage {
    private let scroll = UIScrollView()
    private let column = UIStackView()
    private let hero = CarNowPlayingCard()
    private let recentHeader = CarHomePage.sectionLabel(NSLocalizedString("Recently played", comment: "Row"))
    private let recentRows = UIStackView()
    private var subscriptions = Set<AnyCancellable>()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "AutoBridge"
        scroll.alwaysBounceVertical = true
        scroll.showsVerticalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        column.axis = .vertical
        column.spacing = 12
        column.isLayoutMarginsRelativeArrangement = true
        column.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 12, bottom: 20, trailing: 12)
        column.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(column)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            column.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            column.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            column.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor),
            column.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor),
        ])

        hero.onOpen = { [weak self] in self?.screen?.showPlayer() }
        column.addArrangedSubview(hero)
        column.addArrangedSubview(Self.sectionLabel(NSLocalizedString("Quick access", comment: "Car home")))
        column.addArrangedSubview(quickAccess())
        column.setCustomSpacing(18, after: column.arrangedSubviews.last!)
        column.addArrangedSubview(recentHeader)
        recentRows.axis = .vertical
        recentRows.spacing = 12
        column.addArrangedSubview(recentRows)

        stores.playback.$current
            .receive(on: RunLoop.main)
            .sink { [weak self] track in self?.hero.isHidden = track == nil }
            .store(in: &subscriptions)
        stores.history.$recent
            .combineLatest(stores.history.$favorites)
            .receive(on: RunLoop.main)
            .sink { [weak self] recent, favorites in self?.reloadRecent(recent, favorites) }
            .store(in: &subscriptions)
    }

    static func sectionLabel(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = CarStyle.font(18, .semibold)
        label.textColor = CarStyle.section
        return label
    }

    /// The six cards, three to a line, in the Android car-home order.
    private func quickAccess() -> UIView {
        let sections = HomeSection.carQuickAccess.filter { section in
            !BuildFlavor.isStore || section.iptvKind != nil
        }
        let cards = sections.map { section -> CarTileButton in
            let card = CarTileButton()
            card.configure(
                CarTile(title: section.plainTitle, symbol: section.carSymbol, tint: section.carTint) { [weak self] in
                    self?.open(section)
                },
                shape: .card
            )
            card.heightAnchor.constraint(equalToConstant: 96).isActive = true
            return card
        }
        return Self.lines(of: cards, across: 3)
    }

    private func open(_ section: HomeSection) {
        guard let screen else { return }
        switch section {
        case .tv: screen.push(CarSourcesPage(kind: .tv))
        case .radio: screen.push(CarSourcesPage(kind: .radio))
        case .youtube, .youtubeMusic: screen.openBrowser(section.webUrl)
        case .streaming:
            screen.openBrowser()
            screen.browser.showSites()
        default: screen.openBrowser()
        }
    }

    /// Favourites first, then what was played lately, streams only, six at most.
    private func reloadRecent(_ recent: [IptvHistoryItem], _ favorites: [IptvHistoryItem]) {
        var seen = Set<String>()
        let items = (favorites + recent.filter { $0.playback == .stream })
            .filter { seen.insert($0.url).inserted }
            .prefix(6)
        let tracks = items.map(PlaybackController.Track.init(item:))
        let rows = items.enumerated().map { index, item -> CarTileButton in
            let row = CarTileButton()
            let section: HomeSection = item.kind == .radio ? .radio : .tv
            row.configure(
                CarTile(
                    title: item.title,
                    subtitle: section.plainTitle,
                    logo: item.logo,
                    symbol: section.carSymbol,
                    tint: section.carTint
                ) { [weak self] in self?.screen?.play(Array(tracks), index: index) },
                shape: .row
            )
            row.heightAnchor.constraint(equalToConstant: 68).isActive = true
            return row
        }
        recentRows.arrangedSubviews.forEach { $0.removeFromSuperview() }
        recentRows.addArrangedSubview(Self.lines(of: rows, across: 3))
        recentHeader.isHidden = rows.isEmpty
    }

    /// Lays [views] out [across] to a line, the last line padded so every cell keeps its width.
    static func lines(of views: [UIView], across: Int) -> UIView {
        let lines = UIStackView()
        lines.axis = .vertical
        lines.spacing = 12
        for start in stride(from: 0, to: views.count, by: across) {
            let line = UIStackView(arrangedSubviews: Array(views[start..<min(start + across, views.count)]))
            line.axis = .horizontal
            line.spacing = 12
            line.distribution = .fillEqually
            while line.arrangedSubviews.count < across { line.addArrangedSubview(UIView()) }
            lines.addArrangedSubview(line)
        }
        return lines
    }
}

/// The Now Playing card at the top of the car home: artwork, what is playing, and its controls.
/// Pressing the artwork or the title opens the full-screen player.
final class CarNowPlayingCard: UIView {
    var onOpen: (() -> Void)?
    private let open = UIControl()
    private let art = UIImageView()
    private let caption = UILabel()
    private let titleLabel = UILabel()
    private var playButton: UIButton!
    private var subscriptions = Set<AnyCancellable>()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = CarStyle.surface
        layer.cornerRadius = 22
        layer.cornerCurve = .continuous

        art.contentMode = .scaleAspectFit
        art.backgroundColor = CarStyle.mix(CarStyle.surface, HomeSection.tv.carTint, 0.18)
        art.layer.cornerRadius = 16
        art.clipsToBounds = true
        art.tintColor = HomeSection.tv.carTint
        caption.text = NSLocalizedString("Now playing", comment: "Car screen")
        caption.font = CarStyle.font(14, .semibold)
        caption.textColor = CarStyle.wordmark
        titleLabel.font = CarStyle.font(21, .semibold)
        titleLabel.textColor = CarStyle.text
        let labels = UIStackView(arrangedSubviews: [caption, titleLabel])
        labels.axis = .vertical
        labels.spacing = 4
        let info = UIStackView(arrangedSubviews: [art, labels])
        info.axis = .horizontal
        info.alignment = .center
        info.spacing = 16
        info.isUserInteractionEnabled = false
        info.translatesAutoresizingMaskIntoConstraints = false
        open.addSubview(info)
        open.layer.cornerRadius = 16
        open.addAction(UIAction { [weak self] _ in self?.onOpen?() }, for: .touchUpInside)

        let playback = { AutoBridgeStores.shared.playback }
        playButton = carIconButton("pause.fill", size: 64) { playback().toggle() }
        playButton.backgroundColor = CarStyle.accent
        playButton.tintColor = CarStyle.onAccent
        let controls = UIStackView(arrangedSubviews: [
            carIconButton("backward.fill", size: 52) { playback().previous() },
            playButton,
            carIconButton("forward.fill", size: 52) { playback().next() },
        ])
        controls.axis = .horizontal
        controls.alignment = .center
        controls.spacing = 14

        let row = UIStackView(arrangedSubviews: [open, controls])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 16
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            art.widthAnchor.constraint(equalToConstant: 72),
            art.heightAnchor.constraint(equalToConstant: 72),
            info.leadingAnchor.constraint(equalTo: open.leadingAnchor, constant: 4),
            info.trailingAnchor.constraint(equalTo: open.trailingAnchor, constant: -4),
            info.topAnchor.constraint(equalTo: open.topAnchor, constant: 4),
            info.bottomAnchor.constraint(equalTo: open.bottomAnchor, constant: -4),
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -18),
            row.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            row.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
        ])

        playback().$current
            .receive(on: RunLoop.main)
            .sink { [weak self] track in self?.update(track) }
            .store(in: &subscriptions)
        playback().$isPlaying
            .receive(on: RunLoop.main)
            .sink { [weak self] playing in
                self?.playButton.setImage(UIImage(systemName: playing ? "pause.fill" : "play.fill"), for: .normal)
            }
            .store(in: &subscriptions)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private func update(_ track: PlaybackController.Track?) {
        guard let track else { return }
        titleLabel.text = track.title
        let section: HomeSection = track.isVideo ? .tv : .radio
        art.tintColor = section.carTint
        art.image = UIImage(systemName: section.carSymbol)
        guard !track.logo.isEmpty else { return }
        if let ready = ImageLoader.shared.cached(track.logo) {
            art.image = ready
            return
        }
        Task { [weak self] in
            guard let image = await ImageLoader.shared.load(track.logo),
                  AutoBridgeStores.shared.playback.current?.logo == track.logo else { return }
            self?.art.image = image
        }
    }
}

// MARK: - Channels

/// The TV or Radio card: that kind's sources, as on the phone.
final class CarSourcesPage: CarGridPage {
    private let kind: IptvKind
    private var subscriptions = Set<AnyCancellable>()

    init(kind: IptvKind) {
        self.kind = kind
        super.init(shape: .row)
        title = kind == .radio
            ? NSLocalizedString("Radio", comment: "Home section")
            : NSLocalizedString("TV", comment: "Home section")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        stores.sources.$sources
            .receive(on: RunLoop.main)
            .sink { [weak self] sources in self?.reload(sources) }
            .store(in: &subscriptions)
    }

    private func reload(_ sources: [IptvSource]) {
        let tiles = sources.filter { $0.kind == kind }.map(tile(for:))
        grid.show(
            [CarTileSection(header: nil, tiles: tiles)],
            emptyText: NSLocalizedString("Add a source on your phone first.", comment: "Car screen")
        )
    }

    private func tile(for source: IptvSource) -> CarTile {
        let section: HomeSection = kind == .radio ? .radio : .tv
        return CarTile(
            title: source.name,
            subtitle: subtitle(for: source),
            symbol: section.carSymbol,
            tint: section.carTint
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
                tint: (source.kind == .radio ? HomeSection.radio : .tv).carTint
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

/// Every starred and recently played channel, from the grid button at the top right of the home.
final class CarFavoritesPage: CarGridPage {
    private var subscriptions = Set<AnyCancellable>()

    init() {
        super.init(shape: .tile)
        title = NSLocalizedString("Favorites", comment: "Home section")
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
                section(NSLocalizedString("Favorites", comment: "Home section"), favorites),
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

    /// The grid of video sites, for the Streaming card.
    func showSites() {
        loadViewIfNeeded()
        setHome(true)
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
