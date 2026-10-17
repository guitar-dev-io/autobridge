import SwiftUI

/// The AutoBridge iOS home: the shared `HomeSection` dashboard as a two-column grid, with the
/// now-playing bar pinned under it and Settings in the navigation bar.
///
/// Settings is not a tile for the same reason the Android phone grid leaves it out: it is reachable
/// from every screen already, and listing it twice pushes the actual content further down.
/// The phone home, laid out like the Android home (`design/reference/v0.4.49/01-home.jpg`): the
/// AutoBridge header with the version and Settings, the car connection card, the quick-open grid
/// of tonal icons, and the field that sends a page or a search to the car screen.
struct HomeView: View {
    @EnvironmentObject private var historyStore: IptvHistoryStore
    @EnvironmentObject private var weather: WeatherStore
    @ObservedObject private var car = CarConnectionState.shared
    @ObservedObject private var carWeb = CarWebScreen.shared
    @State private var address = ""
    @State private var openedURL: URL?
    @State private var showsBrowser = false

    /// Nine across three lines, as on Android; everything else is under More.
    private static let quickOpen: [HomeSection] = [
        .web, .youtube, .youtubeMusic, .tv, .radio, .streaming, .favorites, .folders,
    ]
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 14), count: 3)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    carCard
                    sectionTitle(NSLocalizedString("Quick open", comment: "Home"))
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(Self.quickOpen) { section in
                            NavigationLink {
                                SectionDestination(section: section)
                            } label: {
                                quickTile(title: section.plainTitle, symbol: section.systemImage, accent: section.accent)
                            }
                            .buttonStyle(.plain)
                        }
                        NavigationLink {
                            MoreView()
                        } label: {
                            quickTile(
                                title: NSLocalizedString("More", comment: "Home"),
                                symbol: "square.grid.3x3.fill",
                                accent: AutoBridgeDesign.accentSystem
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    sectionTitle(NSLocalizedString("Send to the car", comment: "Home"))
                    sendField
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
            .task { await weather.refresh() }
            .navigationDestination(isPresented: $showsBrowser) {
                BrowserView(initialURL: openedURL, title: HomeSection.web.plainTitle)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .tint(AutoBridgeDesign.accent)
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(uiImage: Self.appIcon)
                .resizable()
                .frame(width: 56, height: 56)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "AutoBridge")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                Text(verbatim: "v" + Self.version)
                    .font(.subheadline)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
            }
            Spacer()
            NavigationLink {
                SettingsView()
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .frame(width: 52, height: 52)
                    .background(AutoBridgeDesign.surface, in: Circle())
            }
            .accessibilityLabel(Text("Settings"))
        }
        .padding(.top, 8)
    }

    /// Whether the car is connected, and what the car screen is showing.
    private var carCard: some View {
        InkCard(accent: AutoBridgeDesign.signalGood) {
            HStack(spacing: 14) {
                TonalChip(symbol: "car.fill", accent: AutoBridgeDesign.signalGood, size: 56)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: "CarPlay")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(AutoBridgeDesign.primaryText)
                    Text(carDetail)
                        .font(.subheadline)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                HStack(spacing: 6) {
                    Circle()
                        .fill(car.connected ? AutoBridgeDesign.signalGood : AutoBridgeDesign.secondaryText)
                        .frame(width: 8, height: 8)
                    Text(carStatus)
                        .font(.footnote)
                        .foregroundStyle(car.connected ? AutoBridgeDesign.signalGood : AutoBridgeDesign.secondaryText)
                        .lineLimit(1)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(AutoBridgeDesign.surfaceRaised, in: Capsule())
                .fixedSize()
            }
            .padding(16)
        }
    }

    private var carStatus: String {
        car.connected
            ? NSLocalizedString("Connected", comment: "Car status")
            : NSLocalizedString("Not connected", comment: "Car status")
    }

    private var carDetail: String {
        if car.connected, !carWeb.pageTitle.isEmpty { return carWeb.pageTitle }
        return carStatus
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .tracking(1.2)
            .foregroundStyle(AutoBridgeDesign.secondaryText)
            .padding(.top, 8)
    }

    private func quickTile(title: String, symbol: String, accent: Color) -> some View {
        VStack(spacing: 12) {
            TonalChip(symbol: symbol, accent: accent, size: 52)
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AutoBridgeDesign.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .padding(.horizontal, 8)
        .background(AutoBridgeDesign.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(Rectangle())
    }

    /// A page or a search goes to the car screen when one is connected, and opens in the phone's
    /// browser otherwise.
    private var sendField: some View {
        HStack(spacing: 12) {
            TextField(NSLocalizedString("Search or paste a URL", comment: "Home"), text: $address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.webSearch)
                .submitLabel(.send)
                .onSubmit(send)
                .foregroundStyle(AutoBridgeDesign.primaryText)
                .padding(.horizontal, 16)
                .frame(height: 58)
                .background(AutoBridgeDesign.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(AutoBridgeDesign.accent, lineWidth: 2)
                )
            Button(action: send) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(AutoBridgeDesign.onAccent)
                    .frame(width: 64, height: 58)
                    .background(AutoBridgeDesign.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .accessibilityLabel(Text("Send to car"))
            .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func send() {
        guard let url = WebViewModel.resolve(address) else { return }
        if carWeb.isConnected {
            carWeb.show(url)
        } else {
            openedURL = url
            showsBrowser = true
        }
        address = ""
    }

    private static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    private static var appIcon: UIImage {
        guard let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
              let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
              let files = primary["CFBundleIconFiles"] as? [String],
              let last = files.last,
              let image = UIImage(named: last) else { return UIImage(systemName: "car.fill") ?? UIImage() }
        return image
    }
}

/// Every home section as a row, like the Android More page.
struct MoreView: View {
    var body: some View {
        PageShell(
            title: NSLocalizedString("More", comment: "Home"),
            subtitle: NSLocalizedString("Everything AutoBridge can open", comment: "Home")
        ) {
            VStack(spacing: 12) {
                ForEach(HomeSection.allCases) { section in
                    NavigationLink {
                        SectionDestination(section: section)
                    } label: {
                        HStack(spacing: 14) {
                            TonalChip(symbol: section.systemImage, accent: section.accent, size: 52)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(section.title)
                                    .font(.system(size: 17, weight: .medium))
                                    .foregroundStyle(AutoBridgeDesign.primaryText)
                                Text(section.caption)
                                    .font(.subheadline)
                                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(AutoBridgeDesign.secondaryText)
                        }
                        .padding(14)
                        .background(AutoBridgeDesign.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
    }
}

/// Whether CarPlay is connected, for the home's car card. Set by `CarPlaySceneDelegate`.
@MainActor
final class CarConnectionState: ObservableObject {
    static let shared = CarConnectionState()
    @Published var connected = false
}

/// Routes a home tile to its screen. Kept out of `HomeView` so the CarPlay scene and the Settings
/// screen can reach the same destinations.
struct SectionDestination: View {
    let section: HomeSection

    var body: some View {
        switch section {
        case .tv, .radio:
            SourcesView(kind: section.iptvKind ?? .tv, accent: section.accent)
        case .web:
            BrowserView(initialURL: nil, title: section.plainTitle)
        case .youtube, .youtubeMusic:
            BrowserView(initialURL: section.webUrl, title: section.plainTitle)
        case .streaming:
            StreamingView()
        case .folders:
            LocalMediaView(mode: .files)
        case .gallery:
            LocalMediaView(mode: .gallery)
        case .favorites:
            FavoritesView()
        case .weather:
            WeatherView()
        }
    }
}
