import SwiftUI

/// The AutoBridge phone design language, ported from the Android `AutoBridgeDesign` so the two
/// apps read as one product: ink surfaces, hairline borders, and a per-section accent that carries
/// from the home card into that section's screens and player.
///
/// The colour values are the Android constants, not approximations of them.
enum AutoBridgeDesign {
    static let ink = Color(hex: 0x0B0E14)
    static let surface = Color(hex: 0x141924)
    static let surfaceRaised = Color(hex: 0x1C2331)
    static let hairline = Color(hex: 0x26304A)
    static let primaryText = Color(hex: 0xEAF0FA)
    static let secondaryText = Color(hex: 0x8494B0)
    static let accent = Color(hex: 0x4C7DF0)
    static let accentSoft = Color(hex: 0x33C9D6)
    static let danger = Color(hex: 0xFF6B81)

    /// The three readings a channel check has. `StreamPing` names the tone; the colours live here.
    static let signalGood = Color(hex: 0x5BD98A)
    static let signalSlow = Color(hex: 0xFFC65C)

    static let accentTV = Color(hex: 0x6EA8FF)
    static let accentRadio = Color(hex: 0xFFB35C)
    static let accentWeb = Color(hex: 0x7DD3C0)
    static let accentVideo = Color(hex: 0xFF7A8A)
    static let accentFiles = Color(hex: 0x9BE08A)
    static let accentFavorite = Color(hex: 0xFF8FB1)
    static let accentSystem = Color(hex: 0xB39DFF)
    static let accentWeather = Color(hex: 0x7CC4FF)

    static func color(for tone: StreamPing.Tone) -> Color {
        switch tone {
        case .good: return signalGood
        case .slow: return signalSlow
        case .bad: return danger
        }
    }

    /// A check result as the text and the colour a row reads it in.
    static func status(for result: StreamPing.Result) -> (text: String, color: Color) {
        (StreamPing.describe(result), color(for: StreamPing.tone(result)))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

// MARK: - Shared chrome

/// Hairline-bordered ink card with the section accent down its leading edge. Used by the home grid.
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

/// One action in a page's action strip.
struct PageAction: Identifiable {
    let title: String
    let perform: () -> Void

    var id: String { title }
}

/// A glyph button in the page header — the signal icon, and nothing else so far.
struct HeaderAction: Identifiable {
    let glyph: String
    let tint: Color?
    let perform: () -> Void

    var id: String { glyph }
}

/// A page in the house style: header, optional action strip and search field, then the content.
///
/// This is the SwiftUI shape of the Android `LibraryActivity.render`, which every library page goes
/// through. Keeping it in one place is what makes the pages match each other and the car screens.
struct PageShell<Content: View>: View {
    let title: String
    var subtitle: String = ""
    var accent: Color = AutoBridgeDesign.accent
    var actions: [PageAction] = []
    var headerActions: [HeaderAction] = []
    var searchText: Binding<String>?
    var searchPrompt: String = "Search"
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            header
            if let searchText {
                SearchField(text: searchText, prompt: searchPrompt, accent: accent)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
            }
            if !actions.isEmpty {
                actionStrip
            }
            ScrollView {
                content()
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
            }
        }
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        // No `navigationTitle`: the page draws its own header, and setting one too would print the
        // same words twice. The bar is left to the back button and whatever toolbar items a screen
        // adds, which is how the Android pages read.
        .navigationBarTitleDisplayMode(.inline)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }
            Spacer()
            ForEach(headerActions) { action in
                Button(action: action.perform) {
                    Text(action.glyph)
                        .font(.title3)
                        .foregroundStyle(action.tint ?? AutoBridgeDesign.secondaryText)
                        .frame(width: 40, height: 40)
                        .background(AutoBridgeDesign.surface, in: Circle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private var actionStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(actions) { action in
                    Button(action: action.perform) {
                        Text(action.title)
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(AutoBridgeDesign.surfaceRaised, in: Capsule())
                            .foregroundStyle(accent)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
    }
}

/// The pinned search field. Separate so the pages that need one all get the same thing.
struct SearchField: View {
    @Binding var text: String
    var prompt: String
    var accent: Color

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(AutoBridgeDesign.primaryText)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AutoBridgeDesign.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
        )
        .tint(accent)
    }
}

/// One list row: a logo or a badge, a title, a subtitle, and an optional trailing glyph.
struct ContentRow: View {
    let title: String
    var subtitle: String = ""
    var accent: Color = AutoBridgeDesign.accent
    var badge: String = ""
    var logo: String = ""
    var status: (text: String, color: Color)?
    var trailing: String = "›"

    var body: some View {
        HStack(spacing: 12) {
            if logo.isEmpty {
                badgeView
            } else {
                LogoImage(url: logo, title: title, accent: accent)
                    .frame(width: 52, height: 38)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .lineLimit(1)
                if let status {
                    Text(status.text)
                        .font(.caption)
                        .foregroundStyle(status.color)
                        .lineLimit(1)
                } else if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if !trailing.isEmpty {
                Text(trailing)
                    .font(.body)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AutoBridgeDesign.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(Rectangle())
    }

    private var badgeView: some View {
        Text(badge.isEmpty ? initial : badge)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(accent)
            .frame(width: 52, height: 38)
            .background(accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var initial: String {
        guard let first = title.trimmingCharacters(in: .whitespaces).first else { return "•" }
        return String(first).uppercased()
    }
}

/// One grid tile: the logo, the title, the last check result in colour, and a corner glyph.
///
/// Channels and films carry a logo worth seeing, so the entries page is a grid of these rather than
/// a list of rows — two columns is what fits a phone at a glance, like the Android page.
struct ContentTile: View {
    let title: String
    var subtitle: String = ""
    var accent: Color = AutoBridgeDesign.accent
    var logo: String = ""
    var status: (text: String, color: Color)?
    var corner: String = ""
    var onCorner: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .topTrailing) {
                LogoImage(url: logo, title: title, accent: accent, cornerRadius: 12)
                    .frame(height: 78)
                    .frame(maxWidth: .infinity)
                if !corner.isEmpty {
                    Button {
                        onCorner?()
                    } label: {
                        Text(corner)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(accent)
                            .frame(width: 28, height: 28)
                            .background(AutoBridgeDesign.ink.opacity(0.75), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .padding(6)
                }
            }
            Text(title)
                .font(.subheadline)
                .foregroundStyle(AutoBridgeDesign.primaryText)
                .lineLimit(2, reservesSpace: true)
                .multilineTextAlignment(.leading)
            if let status {
                Text(status.text)
                    .font(.caption2)
                    .foregroundStyle(status.color)
                    .lineLimit(1)
            } else if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .lineLimit(1)
            } else {
                // Keeps every tile the same height whether or not it has a second line.
                Text(verbatim: " ")
                    .font(.caption2)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AutoBridgeDesign.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(Rectangle())
    }
}

/// A group heading inside a list, like the Streaming page's "Video" / "Music".
struct SectionLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundStyle(AutoBridgeDesign.secondaryText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 10)
            .padding(.bottom, 2)
    }
}

/// What a page shows instead of rows when it has none, with at most one way forward.
struct EmptyState: View {
    let title: String
    let message: String
    var accent: Color = AutoBridgeDesign.accent
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Text(title)
                .font(.headline)
                .foregroundStyle(AutoBridgeDesign.primaryText)
            Text(message)
                .font(.callout)
                .multilineTextAlignment(.center)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(accent.opacity(0.18), in: Capsule())
                    .foregroundStyle(accent)
                    .buttonStyle(.plain)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .padding(.horizontal, 20)
    }
}

/// `n thing` / `n things`, the way the Android pages count.
///
/// The noun is the key: `plural.source` carries "%d sources" in English and its own form in every
/// other language, so a count never reads as an English word glued to a translated sentence. A noun
/// with no entry in the table falls back to the English rule, which is what keeps adding one cheap.
func plural(_ count: Int, _ singular: String, _ plural: String? = nil) -> String {
    let key = "plural." + singular
    let format = NSLocalizedString(key, comment: "A count of things")
    if format != key { return String(format: format, count) }
    return "\(count) " + (count == 1 ? singular : (plural ?? singular + "s"))
}

/// The host of an address, for the second line of a row.
func hostOf(_ url: String) -> String {
    URL(string: url)?.host ?? url
}
