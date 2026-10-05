import Combine
import SwiftUI
import WebKit

/// In-app browser backed by `WKWebView`.
///
/// Used for the "Web browser" home section, for the YouTube and YouTube Music tiles, for every
/// Streaming site, and for playlist entries that point at a watch page rather than a direct stream.
/// It carries the pieces of the Android browser that iOS can have: an address bar, back/forward,
/// a bookmark star, a desktop-site switch, and the YouTube add-ons — which run from the same
/// implementation here as they do on Android, one `WKUserScript`-free evaluation per navigation.
struct BrowserView: View {
    let initialURL: URL?
    var title: String = NSLocalizedString("Web browser", comment: "Home section")

    @EnvironmentObject private var bookmarkStore: WebBookmarkStore
    @EnvironmentObject private var youtube: YouTubeSettings
    @StateObject private var model = WebViewModel()

    @State private var address = ""
    @State private var editingAddress = false

    var body: some View {
        VStack(spacing: 0) {
            addressBar
            ZStack {
                if model.hasContent {
                    WebView(model: model)
                } else {
                    startPage
                }
                if model.isLoading {
                    ProgressView()
                        .tint(AutoBridgeDesign.accent)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 8)
                        .frame(maxHeight: .infinity, alignment: .top)
                }
            }
            controlBar
        }
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        .navigationTitle(model.pageTitle.isEmpty ? title : model.pageTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.attach(youtube: youtube)
            if let initialURL, !model.hasContent {
                model.load(initialURL)
                address = initialURL.absoluteString
            }
        }
        .onChange(of: model.currentURL) { url in
            if !editingAddress { address = url }
        }
        .onDisappear { model.suspend() }
    }

    // MARK: - Chrome

    private var addressBar: some View {
        HStack(spacing: 8) {
            Image(systemName: model.isSecure ? "lock.fill" : "globe")
                .font(.caption)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            TextField(
                NSLocalizedString("Address or search", comment: "Browser"),
                text: $address,
                onEditingChanged: { editingAddress = $0 }
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.webSearch)
            .submitLabel(.go)
            .foregroundStyle(AutoBridgeDesign.primaryText)
            .onSubmit {
                editingAddress = false
                model.go(to: address)
            }
            if !model.currentURL.isEmpty {
                Button {
                    bookmarkStore.toggle(
                        title: model.pageTitle.isEmpty ? model.currentURL : model.pageTitle,
                        url: model.currentURL
                    )
                } label: {
                    Image(
                        systemName: bookmarkStore.contains(url: model.currentURL)
                            ? "star.fill"
                            : "star"
                    )
                    .foregroundStyle(AutoBridgeDesign.accentFavorite)
                }
                .buttonStyle(.plain)
            }
            Menu {
                Toggle(
                    NSLocalizedString("Desktop site", comment: "Browser"),
                    isOn: Binding(get: { model.desktopSite }, set: { model.setDesktopSite($0) })
                )
                if YouTubeUrls.isYouTube(model.currentURL) {
                    Section(NSLocalizedString("YouTube add-ons", comment: "Browser")) {
                        Toggle(
                            NSLocalizedString("SponsorBlock", comment: "YouTube add-on"),
                            isOn: $youtube.sponsorBlockEnabled
                        )
                        Toggle(
                            NSLocalizedString("Highest quality", comment: "YouTube add-on"),
                            isOn: $youtube.autoHighestQuality
                        )
                        Toggle(
                            NSLocalizedString("Skip ads", comment: "YouTube add-on"),
                            isOn: $youtube.adSkipEnabled
                        )
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(AutoBridgeDesign.surface)
        .overlay(alignment: .bottom) {
            Rectangle().fill(AutoBridgeDesign.hairline).frame(height: 1)
        }
    }

    private var controlBar: some View {
        HStack(spacing: 28) {
            Button { model.goBack() } label: { Image(systemName: "chevron.left") }
                .disabled(!model.canGoBack)
            Button { model.goForward() } label: { Image(systemName: "chevron.right") }
                .disabled(!model.canGoForward)
            Spacer()
            Button {
                model.isLoading ? model.stop() : model.reload()
            } label: {
                Image(systemName: model.isLoading ? "xmark" : "arrow.clockwise")
            }
        }
        .font(.title3)
        .foregroundStyle(AutoBridgeDesign.primaryText)
        .buttonStyle(.plain)
        .padding(.horizontal, 28)
        .padding(.vertical, 12)
        .background(AutoBridgeDesign.surface)
    }

    /// What the Web browser tile opens on: the pages the user saved, then the streaming sites, so
    /// the section is useful before anything is typed. The Android browser's start page does the
    /// same job.
    private var startPage: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                if !bookmarkStore.bookmarks.isEmpty {
                    SectionLabel(text: NSLocalizedString("Saved pages", comment: "Browser"))
                    ForEach(bookmarkStore.bookmarks) { bookmark in
                        Button {
                            model.go(to: bookmark.url)
                            address = bookmark.url
                        } label: {
                            ContentRow(
                                title: bookmark.title,
                                subtitle: hostOf(bookmark.url),
                                accent: AutoBridgeDesign.accentWeb,
                                badge: "@"
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                SectionLabel(text: NSLocalizedString("Streaming", comment: "Home section"))
                ForEach(StreamingLinks.all) { link in
                    Button {
                        model.load(link.url)
                        address = link.url.absoluteString
                    } label: {
                        ContentRow(
                            title: link.title,
                            subtitle: hostOf(link.url.absoluteString),
                            accent: AutoBridgeDesign.accentVideo
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
    }
}

/// Observable wrapper that lets SwiftUI drive and observe a single `WKWebView`.
@MainActor
final class WebViewModel: NSObject, ObservableObject {
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var isLoading = false
    @Published var hasContent = false
    @Published var currentURL = ""
    @Published var pageTitle = ""
    @Published var isSecure = false
    @Published private(set) var desktopSite = false

    let webView: WKWebView

    private var enhancer: YouTubeEnhancer?
    private var observations: Set<AnyCancellable> = []

    /// Safari's own iPad agent, which is what a site checks before serving its desktop layout.
    private static let desktopAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.allowsPictureInPictureMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true

        // YouTube rewrites the address with pushState and finishes no navigation when it does, so
        // the add-ons are driven by the address changing as well as by a load completing. This is
        // the iOS counterpart of Android's `doUpdateVisitedHistory`.
        webView.publisher(for: \.url)
            .receive(on: RunLoop.main)
            .sink { [weak self] url in
                guard let self else { return }
                let address = url?.absoluteString ?? ""
                self.currentURL = address
                self.isSecure = address.lowercased().hasPrefix("https://")
                if !address.isEmpty {
                    self.enhancer?.onPageChanged(self.webView, url: address)
                }
            }
            .store(in: &observations)
        webView.publisher(for: \.title)
            .receive(on: RunLoop.main)
            .sink { [weak self] title in self?.pageTitle = title ?? "" }
            .store(in: &observations)
    }

    /// Wires the add-ons. Called from the view, which is where the settings object lives.
    func attach(youtube: YouTubeSettings) {
        guard enhancer == nil else { return }
        enhancer = YouTubeEnhancer(settings: youtube)
    }

    func load(_ url: URL) {
        hasContent = true
        webView.load(URLRequest(url: url))
    }

    /// Loads what the address bar holds, treating anything that is not an address as a web search —
    /// the behaviour of every browser's single field.
    func go(to text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let lower = trimmed.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            if let url = URL(string: trimmed) { load(url) }
            return
        }
        if !trimmed.contains(" "), trimmed.contains("."), let url = URL(string: "https://\(trimmed)") {
            load(url)
            return
        }
        var components = URLComponents(string: "https://duckduckgo.com/")
        components?.queryItems = [URLQueryItem(name: "q", value: trimmed)]
        if let url = components?.url { load(url) }
    }

    func goBack() { webView.goBack() }
    func goForward() { webView.goForward() }
    func reload() { webView.reload() }
    func stop() { webView.stopLoading() }

    func setDesktopSite(_ enabled: Bool) {
        desktopSite = enabled
        webView.customUserAgent = enabled ? Self.desktopAgent : nil
        webView.reload()
    }

    /// Leaves the page loaded but drops in-flight add-on work, so navigating away costs nothing.
    func suspend() {
        enhancer?.cancel()
    }
}

extension WebViewModel: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        isLoading = true
        syncNavigationState()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isLoading = false
        syncNavigationState()
        if let url = webView.url?.absoluteString, !url.isEmpty {
            enhancer?.onPageChanged(webView, url: url)
        }
    }

    func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation!,
        withError error: Error
    ) {
        isLoading = false
        syncNavigationState()
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        isLoading = false
        syncNavigationState()
    }

    private func syncNavigationState() {
        canGoBack = webView.canGoBack
        canGoForward = webView.canGoForward
    }
}

/// `UIViewRepresentable` bridge for the model's web view.
struct WebView: UIViewRepresentable {
    @ObservedObject var model: WebViewModel

    func makeUIView(context: Context) -> WKWebView { model.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
