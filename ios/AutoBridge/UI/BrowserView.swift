import SwiftUI
import WebKit

/// In-app browser backed by `WKWebView`. Used for the "Web browser" home section and for playlist
/// entries that point at a watch page (YouTube/Twitch/etc.) rather than a direct stream. A minimal
/// bar exposes back/forward/reload so a watch page is actually navigable.
struct BrowserView: View {
    let initialURL: URL?

    @StateObject private var model = WebViewModel()

    var body: some View {
        VStack(spacing: 0) {
            WebView(model: model)
            controlBar
        }
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        .onAppear {
            if let initialURL { model.load(initialURL) }
        }
    }

    private var controlBar: some View {
        HStack(spacing: 24) {
            Button { model.goBack() } label: { Image(systemName: "chevron.left") }
                .disabled(!model.canGoBack)
            Button { model.goForward() } label: { Image(systemName: "chevron.right") }
                .disabled(!model.canGoForward)
            Spacer()
            if model.isLoading {
                ProgressView().tint(.white)
            }
            Button { model.reload() } label: { Image(systemName: "arrow.clockwise") }
        }
        .font(.title3)
        .foregroundStyle(.white)
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .background(AutoBridgeDesign.surface)
    }
}

/// Observable wrapper that lets SwiftUI drive and observe a single `WKWebView`.
@MainActor
final class WebViewModel: NSObject, ObservableObject {
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var isLoading = false

    let webView: WKWebView

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    func load(_ url: URL) {
        webView.load(URLRequest(url: url))
    }

    func goBack() { webView.goBack() }
    func goForward() { webView.goForward() }
    func reload() { webView.reload() }
}

extension WebViewModel: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        isLoading = true
        syncNavigationState()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isLoading = false
        syncNavigationState()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
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
