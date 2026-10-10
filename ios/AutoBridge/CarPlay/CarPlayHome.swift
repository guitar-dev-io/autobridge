import Foundation
import UIKit

/// The CarPlay-free half of the car home grid: tile order, glyphs, tints and which remembered
/// channels a browser-backed tile can play. Kept apart from `CarPlaySceneDelegate` so it can be
/// unit tested without a head unit.
extension HomeSection {
    /// The glyph on the car tile, chosen to match the Android car-home reference.
    var carSymbol: String {
        switch self {
        case .tv: return "tv"
        case .radio: return "antenna.radiowaves.left.and.right"
        case .web: return "globe"
        case .youtube: return "play.rectangle.fill"
        case .youtubeMusic: return "play.circle.fill"
        case .streaming: return "play.square.stack.fill"
        default: return systemImage
        }
    }

    /// The tile accent on the car. The phone accents give the three video sites one shared colour,
    /// which would not match the reference, so the car keeps its own.
    var carTint: UIColor {
        switch self {
        case .tv: return UIColor(hex: 0x33C9D6)
        case .radio: return UIColor(hex: 0xFFB35C)
        case .web: return UIColor(hex: 0xB39DFF)
        case .youtube: return UIColor(hex: 0xFF3B30)
        case .youtubeMusic: return UIColor(hex: 0xFF2D95)
        case .streaming: return UIColor(hex: 0xFFD60A)
        default: return UIColor(hex: 0x4C7DF0)
        }
    }
}

enum CarPlayHome {
    /// Tile size in points. CarPlay draws a grid button as image + title only, so the coloured
    /// rounded square of the reference is baked into the image.
    static let tileSize = CGSize(width: 60, height: 60)

    static func tileImage(for section: HomeSection, scale: CGFloat) -> UIImage {
        let tint = section.carTint
        let config = UIImage.SymbolConfiguration(pointSize: 30, weight: .semibold)
        let symbol = (UIImage(systemName: section.carSymbol, withConfiguration: config)
            ?? UIImage(systemName: section.systemImage, withConfiguration: config))?
            .withTintColor(tint, renderingMode: .alwaysOriginal)

        let format = UIGraphicsImageRendererFormat()
        format.scale = scale > 0 ? scale : 2
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: tileSize, format: format)
        let image = renderer.image { _ in
            let rect = CGRect(origin: .zero, size: tileSize)
            tint.withAlphaComponent(0.22).setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: 14).fill()
            guard let symbol else { return }
            // Fit the glyph inside the square with some padding, keeping its aspect ratio.
            let box = rect.insetBy(dx: 12, dy: 12)
            let ratio = min(box.width / symbol.size.width, box.height / symbol.size.height, 1)
            let size = CGSize(width: symbol.size.width * ratio, height: symbol.size.height * ratio)
            symbol.draw(in: CGRect(
                x: rect.midX - size.width / 2,
                y: rect.midY - size.height / 2,
                width: size.width,
                height: size.height
            ))
        }
        return image.withRenderingMode(.alwaysOriginal)
    }

    /// Remembered channels that play in the car for a browser-backed tile: favourites first, then
    /// recents, streams only, de-duplicated by address. YouTube leans to TV channels, YouTube
    /// Music to radio stations, and the web and streaming tiles take both.
    static func playable(
        for section: HomeSection,
        favorites: [IptvHistoryItem],
        recent: [IptvHistoryItem]
    ) -> [IptvHistoryItem] {
        let kind: IptvKind?
        switch section {
        case .youtube: kind = .tv
        case .youtubeMusic: kind = .radio
        default: kind = nil
        }
        var seen = Set<String>()
        return (favorites + recent).filter { item in
            guard item.playback == .stream, !item.url.isEmpty else { return false }
            if let kind, item.kind != kind { return false }
            return seen.insert(item.url).inserted
        }
    }

    /// The honest note a browser-backed tile shows: the site itself opens on the phone.
    static func phoneNote(for section: HomeSection) -> (title: String, subtitle: String)? {
        let channels = NSLocalizedString(
            "Open it on your phone. Starred and recent channels play here.",
            comment: "CarPlay"
        )
        switch section {
        case .web:
            return (NSLocalizedString("Web pages open on your phone", comment: "CarPlay"), channels)
        case .youtube:
            return (
                NSLocalizedString("YouTube plays on your phone", comment: "CarPlay"),
                NSLocalizedString(
                    "Open it on your phone. Starred and recent TV channels play here.",
                    comment: "CarPlay"
                )
            )
        case .youtubeMusic:
            return (
                NSLocalizedString("YouTube Music plays on your phone", comment: "CarPlay"),
                NSLocalizedString(
                    "Open it on your phone. Starred and recent radio stations play here.",
                    comment: "CarPlay"
                )
            )
        case .streaming:
            return (NSLocalizedString("Streaming sites open on your phone", comment: "CarPlay"), channels)
        default:
            return nil
        }
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
