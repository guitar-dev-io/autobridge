import SwiftUI

/// A small slice of the AutoBridge phone design language for iOS: ink surfaces, hairline borders,
/// and a per-section accent that carries from the Home card into that section's screens.
enum AutoBridgeDesign {
    static let ink = Color(red: 0.07, green: 0.08, blue: 0.10)
    static let surface = Color(red: 0.12, green: 0.13, blue: 0.16)
    static let hairline = Color.white.opacity(0.08)
    static let primaryText = Color.white
    static let secondaryText = Color.white.opacity(0.6)

    /// Section accents, matched to the Home dashboard sections.
    static func accent(for section: HomeSection) -> Color {
        switch section {
        case .tv: return Color(red: 0.30, green: 0.65, blue: 1.00)
        case .radio: return Color(red: 1.00, green: 0.55, blue: 0.30)
        case .streaming: return Color(red: 1.00, green: 0.48, blue: 0.54)
        case .browser: return Color(red: 0.45, green: 0.85, blue: 0.55)
        }
    }
}

/// The sections AutoBridge iOS exposes. A subset of the Android home — only the ones iOS can do.
enum HomeSection: String, CaseIterable, Identifiable {
    case tv
    case radio
    case streaming
    case browser

    var id: String { rawValue }

    var title: String {
        switch self {
        case .tv: return "TV"
        case .radio: return "Radio"
        case .streaming: return "Streaming"
        case .browser: return "Web browser"
        }
    }

    /// Secondary line shown on the Home card. Mirrors the Android section captions.
    var caption: String {
        switch self {
        case .tv: return "Live channels & VOD"
        case .radio: return "Audio streams"
        case .streaming: return "YouTube, TikTok, Twitch & more"
        case .browser: return "Full page browsing"
        }
    }

    var systemImage: String {
        switch self {
        case .tv: return "tv"
        case .radio: return "dot.radiowaves.left.and.right"
        case .streaming: return "play.rectangle.on.rectangle"
        case .browser: return "globe"
        }
    }

    var iptvKind: IptvKind? {
        switch self {
        case .tv: return .tv
        case .radio: return .radio
        case .streaming, .browser: return nil
        }
    }
}

/// Hairline-bordered ink card used across the Home grid and section rows.
struct InkCard<Content: View>: View {
    var accent: Color
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .background(AutoBridgeDesign.surface)
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(alignment: .leading) {
                Rectangle()
                    .fill(accent)
                    .frame(width: 3)
                    .clipShape(RoundedRectangle(cornerRadius: 2))
            }
    }
}
