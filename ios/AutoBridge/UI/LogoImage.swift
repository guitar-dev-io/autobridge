import SwiftUI

/// A channel logo, with the row's accent initial as its placeholder.
///
/// Every list on both surfaces draws one of these, so it has to be cheap when the address is blank
/// or dead: `ImageLoader` remembers a failure and this view then simply keeps the initial, which is
/// exactly what the row showed before logos existed.
struct LogoImage: View {
    let url: String
    let title: String
    let accent: Color
    var cornerRadius: CGFloat = 8

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(AutoBridgeDesign.ink)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .padding(4)
            } else {
                Text(initial)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(accent)
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(accent.opacity(0.25), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .task(id: url) { await load() }
    }

    private var initial: String {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        guard let first = trimmed.first else { return "•" }
        return String(first).uppercased()
    }

    private func load() async {
        if url.isEmpty {
            image = nil
            return
        }
        if let ready = ImageLoader.shared.cached(url) {
            image = ready
            return
        }
        image = nil
        image = await ImageLoader.shared.load(url)
    }
}
