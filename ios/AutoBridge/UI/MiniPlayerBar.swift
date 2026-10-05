import SwiftUI

/// The bottom now-playing bar shared by the home screen and the library pages.
///
/// It reads the live `PlaybackController` rather than holding state of its own, so whatever started
/// the audio — a radio channel, a TV stream, or CarPlay — is what the bar shows. It hides itself
/// whenever nothing is loaded. Mirrors the Android `MiniPlayer`.
struct MiniPlayerBar: View {
    @EnvironmentObject private var playback: PlaybackController

    var body: some View {
        if let track = playback.current {
            NavigationLink {
                PlayerView()
            } label: {
                row(track)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .background(AutoBridgeDesign.ink.opacity(0.98))
        }
    }

    private func row(_ track: PlaybackController.Track) -> some View {
        HStack(spacing: 12) {
            LogoImage(
                url: track.logo,
                title: track.title,
                accent: AutoBridgeDesign.accentSoft,
                cornerRadius: 10
            )
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(track.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .lineLimit(1)
                Text(subtitle(track))
                    .font(.caption2)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if playback.queue.count > 1 {
                Button {
                    playback.next()
                } label: {
                    Image(systemName: "forward.end.fill")
                        .foregroundStyle(AutoBridgeDesign.primaryText)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
            }
            Button {
                playback.toggle()
            } label: {
                Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .frame(width: 40, height: 40)
                    .background(AutoBridgeDesign.accent.opacity(0.22), in: Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(10)
        .background(AutoBridgeDesign.surfaceRaised)
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AutoBridgeDesign.hairline, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func subtitle(_ track: PlaybackController.Track) -> String {
        let state = playback.isPlaying
            ? NSLocalizedString("Playing", comment: "Now playing bar")
            : NSLocalizedString("Paused", comment: "Now playing bar")
        let parts = [track.subtitle.isEmpty ? nil : track.subtitle, state].compactMap { $0 }
        return parts.joined(separator: " • ")
    }
}
