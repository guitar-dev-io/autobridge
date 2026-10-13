import AVFoundation
import CarPlay
import Combine
import UIKit

/// A page of the drawn CarPlay screen. A page can take the up/down moves for itself (the browser
/// scrolls its page with them) before they move the focus.
@MainActor
class CarPage: UIViewController {
    weak var screen: CarScreenViewController?

    func handleMove(_ direction: CarDirection) -> Bool { false }
}

/// The CarPlay screen AutoBridge draws itself, laid out like APTV: a tab pill across the top
/// (Channels, Favorites, Quick), a page under it, and a full-screen player over everything while a
/// channel is watched.
///
/// It exists only when CarPlay hands the app a window, which today means the Simulator (whose
/// entitlements declare the navigation and video keys). On an iPhone with the audio entitlement
/// alone there is no window and the template screens are what the car shows.
///
/// It works with touches and without them: every control is a `UIControl`, so a tap presses it,
/// and `CarFocus` lets CarPlay's map buttons, a pan and a rotary knob move between them and press.
@MainActor
final class CarScreenViewController: UIViewController {
    enum Tab: Int, CaseIterable {
        case channels, favorites, quick

        var title: String {
            switch self {
            case .channels: return NSLocalizedString("Channels", comment: "Car screen tab")
            case .favorites: return NSLocalizedString("Favorites", comment: "Car screen tab")
            case .quick: return NSLocalizedString("Quick", comment: "Car screen tab")
            }
        }
    }

    /// Opens CarPlay's own keyboard for an address; set by the scene delegate.
    var onAddress: (() -> Void)?

    private let backButton = carIconButton("chevron.left", size: 40) {}
    private let titleLabel = UILabel()
    private let pill = UIStackView()
    private var tabButtons: [Tab: UIButton] = [:]
    private let header = UIView()
    private let content = UIView()
    private let player = CarPlayerView()
    private var stacks: [Tab: [CarPage]] = [:]
    private var currentTab: Tab = .channels
    private var fullscreen = false
    private var contentTop: NSLayoutConstraint?
    /// True once the car's buttons have been used, so a new page puts the highlight on its first
    /// tile; with touches only there is never a highlight to move.
    private var drivenByButtons = false
    private var pan: CGPoint = .zero

    private lazy var pageFocus = CarFocus(root: view)
    private lazy var playerFocus = CarFocus(root: player)
    private var focus: CarFocus { player.isHidden ? pageFocus : playerFocus }

    private(set) lazy var browser: CarBrowserPage = {
        let page = CarBrowserPage()
        page.screen = self
        return page
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = CarStyle.background

        backButton.addAction(UIAction { [weak self] _ in self?.back() }, for: .touchUpInside)
        titleLabel.font = CarStyle.font(20, .bold)
        titleLabel.textColor = CarStyle.text
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        pill.axis = .horizontal
        pill.spacing = 4
        pill.backgroundColor = CarStyle.surface
        pill.layer.cornerRadius = 22
        pill.isLayoutMarginsRelativeArrangement = true
        pill.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4)
        for tab in Tab.allCases {
            let button = UIButton(type: .custom)
            button.setTitle(tab.title, for: .normal)
            button.titleLabel?.font = CarStyle.font(15, .semibold)
            button.contentEdgeInsets = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
            button.layer.cornerRadius = 18
            button.heightAnchor.constraint(equalToConstant: 36).isActive = true
            button.addAction(UIAction { [weak self] _ in self?.select(tab) }, for: .touchUpInside)
            tabButtons[tab] = button
            pill.addArrangedSubview(button)
        }

        // The pill stays centred whatever the page title, so the tabs never move under a finger.
        let leading = UIStackView(arrangedSubviews: [backButton, titleLabel])
        leading.axis = .horizontal
        leading.alignment = .center
        leading.spacing = 10
        leading.translatesAutoresizingMaskIntoConstraints = false
        pill.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(leading)
        header.addSubview(pill)
        header.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            pill.centerXAnchor.constraint(equalTo: header.centerXAnchor),
            pill.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            leading.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            leading.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            leading.trailingAnchor.constraint(lessThanOrEqualTo: pill.leadingAnchor, constant: -10),
        ])
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)
        view.addSubview(header)

        player.isHidden = true
        player.onClose = { [weak self] in self?.closePlayer(stop: true) }
        player.onHide = { [weak self] in self?.closePlayer(stop: false) }
        player.onControlsHidden = { [weak self] in self?.playerFocus.reset() }
        player.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(player)

        let safe = view.safeAreaLayoutGuide
        let top = content.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8)
        contentTop = top
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: safe.topAnchor, constant: 8),
            header.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 12),
            header.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            header.heightAnchor.constraint(equalToConstant: 44),
            top,
            content.leadingAnchor.constraint(equalTo: safe.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: safe.trailingAnchor),
            content.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            player.topAnchor.constraint(equalTo: view.topAnchor),
            player.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            player.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            player.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        stacks[.channels] = [page(CarSourcesPage())]
        stacks[.favorites] = [page(CarFavoritesPage())]
        stacks[.quick] = [page(CarQuickPage())]
        select(.channels)

        #if DEBUG
        let probe = UITapGestureRecognizer(target: self, action: #selector(logTap(_:)))
        probe.cancelsTouchesInView = false
        view.addGestureRecognizer(probe)
        #endif
    }

    #if DEBUG
    /// Says in the log whether a tap on the CarPlay window reached the app at all.
    @objc private func logTap(_ recognizer: UITapGestureRecognizer) {
        NSLog("AutoBridge CarPlay: screen tap at %@", NSCoder.string(for: recognizer.location(in: view)))
    }
    #endif

    /// Keeps the pages clear of CarPlay's map buttons, which sit over the right edge of the window.
    func adopt(_ window: CPWindow) {
        loadViewIfNeeded()
        if #available(iOS 15.4, *) {
            content.trailingAnchor.constraint(lessThanOrEqualTo: window.mapButtonSafeAreaLayoutGuide.trailingAnchor).isActive = true
        }
    }

    private func page<P: CarPage>(_ page: P) -> P {
        page.screen = self
        return page
    }

    // MARK: Navigation

    func select(_ tab: Tab) {
        currentTab = tab
        for (key, button) in tabButtons {
            let on = key == tab
            button.backgroundColor = on ? CarStyle.text : .clear
            button.setTitleColor(on ? CarStyle.background : CarStyle.text, for: .normal)
        }
        showTop()
    }

    func push(_ page: CarPage) {
        page.screen = self
        stacks[currentTab, default: []].append(page)
        showTop()
    }

    /// Opens the browser page on the Quick tab, for an address typed in CarPlay's keyboard.
    func openBrowser() {
        guard !BuildFlavor.isStore else { return }
        closePlayer(stop: false)
        setFullscreen(false)
        currentTab = .quick
        var stack = stacks[.quick] ?? []
        stack.removeAll { $0 === browser }
        stack.append(browser)
        stacks[.quick] = stack
        select(.quick)
    }

    /// Back, wherever the car's back comes from: out of full screen, then out of the player, then a
    /// page up the current tab.
    func back() {
        if fullscreen {
            setFullscreen(false)
        } else if !player.isHidden {
            closePlayer(stop: false)
        } else if var stack = stacks[currentTab], stack.count > 1 {
            stack.removeLast()
            stacks[currentTab] = stack
            showTop()
        }
    }

    private func showTop() {
        guard let top = stacks[currentTab]?.last else { return }
        for child in children where child !== top {
            child.willMove(toParent: nil)
            child.view.removeFromSuperview()
            child.removeFromParent()
        }
        if top.parent !== self {
            addChild(top)
            top.view.frame = content.bounds
            top.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            content.addSubview(top.view)
            top.didMove(toParent: self)
        }
        titleLabel.text = top.title
        backButton.isHidden = (stacks[currentTab]?.count ?? 0) < 2
        pageFocus.reset()
        focusFirstTile()
    }

    private func focusFirstTile() {
        guard drivenByButtons else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.view.layoutIfNeeded()
            self.pageFocus.focusFirst(in: self.content)
        }
    }

    /// Full screen hides the header, for the browser.
    func setFullscreen(_ on: Bool) {
        fullscreen = on
        header.isHidden = on
        contentTop?.isActive = false
        contentTop = on
            ? content.topAnchor.constraint(equalTo: view.topAnchor)
            : content.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8)
        contentTop?.isActive = true
        browser.setChromeHidden(on)
    }

    // MARK: Playback

    /// Plays [tracks] from [index] and watches it here, full screen, like the phone's player.
    func play(_ tracks: [PlaybackController.Track], index: Int) {
        guard tracks.indices.contains(index) else { return }
        let stores = AutoBridgeStores.shared
        let track = tracks[index]
        stores.playback.play(track, queue: tracks, index: index)
        if let origin = track.origin { stores.history.recordPlayback(origin) }
        showPlayer()
    }

    /// Puts the player over the screen for whatever is playing now.
    func showPlayer() {
        loadViewIfNeeded()
        guard AutoBridgeStores.shared.playback.current != nil else { return }
        player.attach(AutoBridgeStores.shared.playback)
        player.isHidden = false
        view.bringSubviewToFront(player)
        player.showControls()
        if drivenByButtons {
            DispatchQueue.main.async { [weak self] in self?.playerFocus.focusFirst(in: self?.player.controls) }
        }
    }

    private func closePlayer(stop: Bool) {
        if stop { AutoBridgeStores.shared.playback.stop() }
        player.detach()
        player.isHidden = true
        playerFocus.reset()
    }

    var isPlayerShown: Bool { !player.isHidden }

    // MARK: Car controls

    func move(_ direction: CarDirection) {
        drivenByButtons = true
        if !player.isHidden {
            if player.controlsHidden {
                player.showControls()
                return
            }
            player.showControls()
        } else if let top = stacks[currentTab]?.last, top.handleMove(direction) {
            return
        }
        focus.move(direction)
    }

    func activate() {
        drivenByButtons = true
        if !player.isHidden, player.controlsHidden {
            player.showControls()
            return
        }
        player.showControls()
        focus.activate()
    }

    func panBegan() {
        pan = .zero
    }

    /// A drag on the car screen moves the highlight one step per 70 points, along its main axis.
    func panMoved(to translation: CGPoint) {
        let step: CGFloat = 70
        let dx = translation.x - pan.x
        let dy = translation.y - pan.y
        if abs(dx) >= step, abs(dx) > abs(dy) {
            move(dx > 0 ? .right : .left)
            pan = translation
        } else if abs(dy) >= step {
            move(dy > 0 ? .down : .up)
            pan = translation
        }
    }
}

// MARK: - Player

/// The full-screen player: the shared `AVPlayer`'s picture (or the logo, for radio), a title, and
/// a control bar that fades after a few seconds of nothing happening.
@MainActor
final class CarPlayerView: UIView {
    var onClose: (() -> Void)?
    var onHide: (() -> Void)?
    var onControlsHidden: (() -> Void)?

    let controls = UIView()
    private let picture = PlayerLayerView()
    private let logo = UIImageView()
    private let titleLabel = UILabel()
    private let statusLabel = UILabel()
    private var playButton: UIButton!
    private var favoriteButton: UIButton!
    private var subscriptions = Set<AnyCancellable>()
    private var hideTimer: Timer?
    private(set) var controlsHidden = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        picture.playerLayer.videoGravity = .resizeAspect
        picture.frame = bounds
        picture.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(picture)

        logo.contentMode = .scaleAspectFit
        logo.tintColor = CarStyle.secondary
        logo.translatesAutoresizingMaskIntoConstraints = false
        addSubview(logo)

        let shade = UIView()
        shade.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        shade.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(shade)

        titleLabel.font = CarStyle.font(20, .bold)
        titleLabel.textColor = .white
        statusLabel.font = CarStyle.font(14)
        statusLabel.textColor = CarStyle.secondary
        let labels = UIStackView(arrangedSubviews: [titleLabel, statusLabel])
        labels.axis = .vertical
        labels.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(labels)

        let playback = { AutoBridgeStores.shared.playback }
        playButton = carIconButton("pause.fill", size: 56) { playback().toggle() }
        favoriteButton = carIconButton("star", size: 48) { [weak self] in self?.toggleFavorite() }
        let bar = UIStackView(arrangedSubviews: [
            carIconButton("xmark", size: 48) { [weak self] in self?.onClose?() },
            carIconButton("backward.fill", size: 48) { playback().previous() },
            playButton,
            carIconButton("forward.fill", size: 48) { playback().next() },
            favoriteButton,
            carIconButton("chevron.down", size: 48) { [weak self] in self?.onHide?() },
        ])
        bar.axis = .horizontal
        bar.alignment = .center
        bar.spacing = 18
        bar.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(bar)

        controls.translatesAutoresizingMaskIntoConstraints = false
        addSubview(controls)
        let safe = safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            logo.centerXAnchor.constraint(equalTo: centerXAnchor),
            logo.centerYAnchor.constraint(equalTo: centerYAnchor, constant: -30),
            logo.widthAnchor.constraint(equalToConstant: 160),
            logo.heightAnchor.constraint(equalToConstant: 160),
            controls.leadingAnchor.constraint(equalTo: leadingAnchor),
            controls.trailingAnchor.constraint(equalTo: trailingAnchor),
            controls.bottomAnchor.constraint(equalTo: bottomAnchor),
            shade.leadingAnchor.constraint(equalTo: controls.leadingAnchor),
            shade.trailingAnchor.constraint(equalTo: controls.trailingAnchor),
            shade.topAnchor.constraint(equalTo: controls.topAnchor),
            shade.bottomAnchor.constraint(equalTo: controls.bottomAnchor),
            labels.topAnchor.constraint(equalTo: controls.topAnchor, constant: 12),
            labels.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 20),
            labels.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -20),
            bar.topAnchor.constraint(equalTo: labels.bottomAnchor, constant: 10),
            bar.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            bar.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -14),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(toggleControls))
        tap.cancelsTouchesInView = false
        addGestureRecognizer(tap)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func attach(_ playback: PlaybackController) {
        picture.playerLayer.player = playback.player
        subscriptions.removeAll()
        playback.$current
            .receive(on: RunLoop.main)
            .sink { [weak self] track in self?.update(track) }
            .store(in: &subscriptions)
        playback.$isPlaying
            .receive(on: RunLoop.main)
            .sink { [weak self] playing in
                self?.playButton.setImage(UIImage(systemName: playing ? "pause.fill" : "play.fill"), for: .normal)
                self?.scheduleHide()
            }
            .store(in: &subscriptions)
        playback.$failure
            .receive(on: RunLoop.main)
            .sink { [weak self] failure in
                self?.statusLabel.text = failure ?? self?.liveText(playback)
                if failure != nil { self?.showControls() }
            }
            .store(in: &subscriptions)
        AutoBridgeStores.shared.history.$favorites
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.updateFavorite() }
            .store(in: &subscriptions)
    }

    func detach() {
        picture.playerLayer.player = nil
        subscriptions.removeAll()
        hideTimer?.invalidate()
    }

    private func update(_ track: PlaybackController.Track?) {
        titleLabel.text = track?.title
        statusLabel.text = liveText(AutoBridgeStores.shared.playback)
        let video = track?.isVideo ?? false
        picture.isHidden = !video
        logo.isHidden = video
        logo.image = UIImage(systemName: "radio")
        if let url = track?.logo, !url.isEmpty {
            if let ready = ImageLoader.shared.cached(url) {
                logo.image = ready
            } else {
                Task { [weak self] in
                    guard let image = await ImageLoader.shared.load(url),
                          AutoBridgeStores.shared.playback.current?.logo == url else { return }
                    self?.logo.image = image
                }
            }
        }
        updateFavorite()
    }

    private func liveText(_ playback: PlaybackController) -> String {
        guard let track = playback.current else { return "" }
        return track.isVideo
            ? NSLocalizedString("TV", comment: "Home section")
            : NSLocalizedString("Radio", comment: "Home section")
    }

    private func updateFavorite() {
        let url = AutoBridgeStores.shared.playback.current?.url ?? ""
        let on = !url.isEmpty && AutoBridgeStores.shared.history.isFavorite(url: url)
        favoriteButton.setImage(UIImage(systemName: on ? "star.fill" : "star"), for: .normal)
        favoriteButton.tintColor = on ? UIColor(AutoBridgeDesign.accentFavorite) : CarStyle.text
    }

    private func toggleFavorite() {
        guard let origin = AutoBridgeStores.shared.playback.current?.origin else { return }
        _ = AutoBridgeStores.shared.history.toggleFavorite(origin)
        updateFavorite()
    }

    @objc private func toggleControls() {
        controlsHidden ? showControls() : hideControls()
    }

    func showControls() {
        controlsHidden = false
        UIView.animate(withDuration: 0.2) { self.controls.alpha = 1 }
        scheduleHide()
    }

    private func hideControls() {
        controlsHidden = true
        UIView.animate(withDuration: 0.3) { self.controls.alpha = 0 }
        onControlsHidden?()
    }

    /// The bar fades only over a picture that is playing; paused or radio, it stays.
    private func scheduleHide() {
        hideTimer?.invalidate()
        let playback = AutoBridgeStores.shared.playback
        guard playback.isPlaying, playback.failure == nil, playback.current?.isVideo == true else { return }
        hideTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.hideControls() }
        }
    }
}

#if DEBUG
import SwiftUI

/// The drawn CarPlay screen on the phone, for looking at it and tapping through it where no
/// CarPlay display is at hand. Launch with `-carScreenPreview`. It is drawn at a car screen's size
/// (800 × 480 points) and scaled down to fit, so the layout is the one a car would get.
///
/// `-carScreenKeys <letters>` then presses the car's buttons, one a second, to try the screen
/// without touches: u/d/l/r move, s selects, b is back. For example `-carScreenKeys drrs`.
struct CarScreenPreview: UIViewControllerRepresentable {
    static var requested: Bool { CommandLine.arguments.contains("-carScreenPreview") }

    func makeUIViewController(context: Context) -> UIViewController { Host() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    final class Host: UIViewController {
        private let screen = CarScreenViewController()
        private let size = CGSize(width: 800, height: 480)

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            addChild(screen)
            view.addSubview(screen.view)
            screen.didMove(toParent: self)
            pressKeys()
        }

        private func pressKeys() {
            let arguments = CommandLine.arguments
            guard let at = arguments.firstIndex(of: "-carScreenKeys"), at + 1 < arguments.count else { return }
            for (index, key) in arguments[at + 1].enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2 + Double(index)) { [weak self] in
                    guard let screen = self?.screen else { return }
                    switch key {
                    case "u": screen.move(.up)
                    case "d": screen.move(.down)
                    case "l": screen.move(.left)
                    case "r": screen.move(.right)
                    case "s": screen.activate()
                    case "b": screen.back()
                    default: break
                    }
                }
            }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            let scale = min(view.bounds.width / size.width, view.bounds.height / size.height)
            screen.view.transform = .identity
            screen.view.bounds = CGRect(origin: .zero, size: size)
            screen.view.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
            screen.view.transform = CGAffineTransform(scaleX: scale, y: scale)
        }
    }
}
#endif
