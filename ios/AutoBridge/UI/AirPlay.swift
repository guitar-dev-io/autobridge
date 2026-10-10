import AVKit
import Combine
import SwiftUI
import UIKit
import WebKit

/// Video on a car screen over AirPlay: an aftermarket head unit or a CarPlay box that is an AirPlay
/// receiver. Off by default, like the Android advanced options that lift a protection: turning it
/// on is the driver's own choice, after a warning, and it can be turned off from the same place.
///
/// Off, a video's sound still goes to an AirPlay speaker but its picture stays on the phone. On,
/// the picture goes too and keeps playing on that screen while the phone is locked. What the
/// receiving screen does while the car moves is up to that screen; iOS does not gate AirPlay on
/// motion the way it gates video inside CarPlay.
enum CarScreenVideo {
    private static let key = "carScreen.airplayVideo"

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set {
            UserDefaults.standard.set(newValue, forKey: key)
            Task { @MainActor in AutoBridgeStores.shared.playback.applyExternalPlayback() }
        }
    }
}

/// The system AirPlay picker as a toolbar button. It lists video receivers first, so a car screen
/// is at the top of the list rather than under the speakers.
struct AirPlayButton: UIViewRepresentable {
    var tint: UIColor = UIColor(AutoBridgeDesign.primaryText)

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = true
        picker.tintColor = tint
        picker.activeTintColor = UIColor(AutoBridgeDesign.accent)
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// The Settings row for the option, with the warning shown before it turns on.
struct CarScreenVideoSection: View {
    @State private var enabled = CarScreenVideo.enabled
    @State private var confirming = false

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { enabled },
                set: { wanted in
                    if wanted {
                        confirming = true
                    } else {
                        enabled = false
                        CarScreenVideo.enabled = false
                    }
                }
            )) {
                ValueRow(
                    title: NSLocalizedString("Video on a car screen (AirPlay)", comment: "Advanced"),
                    detail: NSLocalizedString("Sends the picture, not only the sound, to an AirPlay screen in the car, and keeps it playing while the phone is locked.", comment: "Advanced")
                )
            }
        } header: {
            Text(NSLocalizedString("Advanced (off by default)", comment: "Advanced"))
        } footer: {
            Text(NSLocalizedString("Turning this on lifts a protection; you take the risk and the responsibility. Watching video the driver can see while the car moves is dangerous and against Thai traffic law.", comment: "Advanced"))
        }
        .alert(
            NSLocalizedString("Video on a car screen (AirPlay)", comment: "Advanced"),
            isPresented: $confirming
        ) {
            Button(NSLocalizedString("Turn on", comment: "Advanced"), role: .destructive) {
                enabled = true
                CarScreenVideo.enabled = true
            }
            Button(NSLocalizedString("Cancel", comment: "Action"), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("The car screen will show video whether the car is parked or moving; nothing here checks. Do not watch while driving. Turn it on only for passengers, or for when you are parked.", comment: "Advanced"))
        }
    }
}

// MARK: - Web pages on a car screen

/// Web pages on an AirPlay car screen. Off by default, like the video option above: on, a car
/// screen that is an AirPlay receiver (an aftermarket head unit, a CarPlay box) gets its own
/// browser window instead of a mirror of the phone, and the browser on the phone sends pages to it.
enum CarScreenWeb {
    private static let key = "carScreen.airplayWeb"

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
}

/// The one web view a car screen shows, shared by the AirPlay screen and the CarPlay window.
///
/// It is a separate `WKWebView` from the phone's, so the phone can keep browsing while the car
/// shows a page. Neither car screen takes touches (an AirPlay screen is a picture, CarPlay hands a
/// navigation-style window no touches), so it is driven from outside: the phone browser sends
/// pages and scrolls it, and on CarPlay the map buttons and pan gestures do.
@MainActor
final class CarWebScreen: ObservableObject {
    static let shared = CarWebScreen()

    /// Where the page is showing. CarPlay wins when both are connected: it is the car's own screen.
    enum Host { case airPlay, carPlay }

    /// A car screen is connected and can show a page.
    @Published private(set) var isConnected = false
    /// The phone browser's page follows onto the car screen as the user browses.
    @Published var followsPhone = false
    @Published private(set) var currentURL = ""
    @Published private(set) var pageTitle = ""

    let webView: WKWebView
    private var hosts: [Host: CarWebViewController] = [:]
    private var observations: Set<AnyCancellable> = []

    private init() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.publisher(for: \.url)
            .receive(on: RunLoop.main)
            .sink { [weak self] url in self?.currentURL = url?.absoluteString ?? "" }
            .store(in: &observations)
        webView.publisher(for: \.title)
            .receive(on: RunLoop.main)
            .sink { [weak self] title in self?.pageTitle = title ?? "" }
            .store(in: &observations)
    }

    func attach(_ controller: CarWebViewController, as host: Host) {
        hosts[host] = controller
        placeWebView()
    }

    func detach(_ host: Host) {
        hosts[host] = nil
        if hosts.isEmpty { followsPhone = false }
        placeWebView()
    }

    /// Puts the one web view in the screen that should show it.
    private func placeWebView() {
        let target = hosts[.carPlay] ?? hosts[.airPlay]
        for controller in hosts.values where controller !== target {
            controller.show(nil)
        }
        target?.show(webView)
        isConnected = target != nil
    }

    func show(_ url: URL) {
        webView.load(URLRequest(url: url))
    }

    /// The phone browser's address changed; the car screen follows when it was asked to.
    func phoneNavigated(to address: String) {
        guard followsPhone, isConnected, address != currentURL, let url = URL(string: address) else { return }
        show(url)
    }

    /// Scrolls by a fraction of the visible height: positive is down the page.
    func scroll(pages: CGFloat) {
        let scrollView = webView.scrollView
        let maxY = max(0, scrollView.contentSize.height - scrollView.bounds.height + scrollView.contentInset.bottom)
        let target = min(max(0, scrollView.contentOffset.y + scrollView.bounds.height * pages), maxY)
        scrollView.setContentOffset(CGPoint(x: scrollView.contentOffset.x, y: target), animated: true)
    }

    /// Scrolls by a distance in points, for a pan on the CarPlay screen.
    func scroll(by delta: CGPoint) {
        let scrollView = webView.scrollView
        let maxX = max(0, scrollView.contentSize.width - scrollView.bounds.width)
        let maxY = max(0, scrollView.contentSize.height - scrollView.bounds.height + scrollView.contentInset.bottom)
        let x = min(max(0, scrollView.contentOffset.x + delta.x), maxX)
        let y = min(max(0, scrollView.contentOffset.y + delta.y), maxY)
        scrollView.setContentOffset(CGPoint(x: x, y: y), animated: false)
    }

    func goBack() { if webView.canGoBack { webView.goBack() } }
    func reload() { webView.reload() }

    /// Stops sending pages and leaves a blank screen, which reads as "nothing here" on the car.
    func stop() {
        followsPhone = false
        webView.loadHTMLString("<html><body style='background:#000'></body></html>", baseURL: nil)
    }
}

/// The car screen's root view: the shared web view when it is here, a short note otherwise.
final class CarWebViewController: UIViewController {
    private let note = UILabel()
    private weak var hosted: WKWebView?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        note.text = NSLocalizedString("Open a page in AutoBridge on your phone and send it to the car screen.", comment: "Car screen")
        note.textColor = .lightGray
        note.font = .preferredFont(forTextStyle: .title2)
        note.numberOfLines = 0
        note.textAlignment = .center
        note.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(note)
        NSLayoutConstraint.activate([
            note.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            note.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            note.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.8),
        ])
    }

    /// Shows [webView] filling the screen, or the note when it is nil (the page is on another screen).
    func show(_ webView: WKWebView?) {
        loadViewIfNeeded()
        if let hosted, hosted !== webView, hosted.superview === view { hosted.removeFromSuperview() }
        hosted = webView
        note.isHidden = webView != nil
        guard let webView else { return }
        webView.removeFromSuperview()
        webView.frame = view.bounds
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(webView)
    }
}

/// The AirPlay car screen's scene. iOS creates it only when `AutoBridgeAppDelegate` offers it,
/// which it does only with `CarScreenWeb` on; otherwise the screen mirrors the phone as before.
@MainActor
@objc(ExternalDisplaySceneDelegate)
final class ExternalDisplaySceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let controller = CarWebViewController()
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.isHidden = false
        self.window = window
        CarWebScreen.shared.attach(controller, as: .airPlay)
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        CarWebScreen.shared.detach(.airPlay)
        window = nil
    }
}

/// Offers the AirPlay car screen its own scene when `CarScreenWeb` is on. Every other scene keeps
/// the configuration it would have had: SwiftUI's phone window, and CarPlay from Info.plist.
final class AutoBridgeAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let role = connectingSceneSession.role
        if role == .windowExternalDisplayNonInteractive, CarScreenWeb.enabled {
            let configuration = UISceneConfiguration(name: "AutoBridge car screen", sessionRole: role)
            configuration.delegateClass = ExternalDisplaySceneDelegate.self
            return configuration
        }
        return connectingSceneSession.configuration
    }
}

/// The Settings row for web pages on an AirPlay car screen, with the same kind of warning.
struct CarScreenWebSection: View {
    @State private var enabled = CarScreenWeb.enabled
    @State private var confirming = false

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { enabled },
                set: { wanted in
                    if wanted {
                        confirming = true
                    } else {
                        enabled = false
                        CarScreenWeb.enabled = false
                    }
                }
            )) {
                ValueRow(
                    title: NSLocalizedString("Web pages on a car screen (AirPlay)", comment: "Advanced"),
                    detail: NSLocalizedString("An AirPlay car screen gets its own browser instead of a mirror of the phone. Send a page from the browser with the car button; reconnect the screen after changing this.", comment: "Advanced")
                )
            }
        }
        .alert(
            NSLocalizedString("Web pages on a car screen (AirPlay)", comment: "Advanced"),
            isPresented: $confirming
        ) {
            Button(NSLocalizedString("Turn on", comment: "Advanced"), role: .destructive) {
                enabled = true
                CarScreenWeb.enabled = true
            }
            Button(NSLocalizedString("Cancel", comment: "Action"), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("The car screen will show web pages, video included, whether the car is parked or moving; nothing here checks. Do not watch while driving.", comment: "Advanced"))
        }
    }
}

/// The phone browser's car-screen controls: send the page, follow along, scroll, stop.
struct CarWebRemote: View {
    @ObservedObject var screen: CarWebScreen
    let phoneURL: String

    var body: some View {
        HStack(spacing: 22) {
            Button {
                if let url = URL(string: phoneURL) { screen.show(url) }
                screen.followsPhone = true
            } label: {
                Label(NSLocalizedString("Send to car", comment: "Car screen"), systemImage: "car.fill")
            }
            .disabled(phoneURL.isEmpty)
            Toggle(isOn: $screen.followsPhone) {
                Image(systemName: "link")
            }
            .toggleStyle(.button)
            Spacer()
            Button { screen.scroll(pages: -0.8) } label: { Image(systemName: "chevron.up") }
            Button { screen.scroll(pages: 0.8) } label: { Image(systemName: "chevron.down") }
            Button { screen.goBack() } label: { Image(systemName: "arrow.uturn.backward") }
            Button { screen.stop() } label: { Image(systemName: "stop.fill") }
        }
        .labelStyle(.iconOnly)
        .font(.body)
        .foregroundStyle(AutoBridgeDesign.primaryText)
        .buttonStyle(.plain)
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .background(AutoBridgeDesign.surface)
        .overlay(alignment: .top) {
            Rectangle().fill(AutoBridgeDesign.hairline).frame(height: 1)
        }
    }
}
