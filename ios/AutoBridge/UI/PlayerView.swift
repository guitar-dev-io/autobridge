import AVKit
import SwiftUI

/// The player screen for whatever `PlaybackController` currently holds.
///
/// Video goes through `VideoPlayer`, so a TV channel or a film gets the native transport, AirPlay
/// and picture-in-picture for free. Audio gets the designed screen instead — artwork, a scrubber,
/// and a LIVE state for the streams that have no duration to scrub — which is the iOS reading of
/// the Android player. Either way the player itself is the shared one, so leaving this screen does
/// not stop a radio station.
struct PlayerView: View {
    @EnvironmentObject private var playback: PlaybackController
    @EnvironmentObject private var historyStore: IptvHistoryStore

    @State private var scrubbing = false
    @State private var scrubPosition: Double = 0

    var body: some View {
        ZStack {
            AutoBridgeDesign.ink.ignoresSafeArea()
            if let track = playback.current {
                if track.isVideo {
                    VideoPlayer(player: playback.player)
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    audioScreen(track)
                }
            } else {
                EmptyState(
                    title: NSLocalizedString("Nothing playing", comment: "Player"),
                    message: NSLocalizedString(
                        "Pick a channel in TV or Radio and it opens here.",
                        comment: "Player"
                    )
                )
            }
        }
        .navigationTitle(playback.current?.title ?? NSLocalizedString("Player", comment: "Player"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let origin = playback.current?.origin {
                    Button {
                        historyStore.toggleFavorite(origin)
                    } label: {
                        Image(
                            systemName: historyStore.isFavorite(url: origin.url)
                                ? "star.fill"
                                : "star"
                        )
                    }
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let failure = playback.failure {
                Text(failure)
                    .font(.caption)
                    .foregroundStyle(AutoBridgeDesign.danger)
                    .padding(12)
                    .background(AutoBridgeDesign.surfaceRaised, in: Capsule())
                    .padding(.bottom, 24)
                    .padding(.horizontal, 16)
            }
        }
    }

    // MARK: - Audio screen

    private func audioScreen(_ track: PlaybackController.Track) -> some View {
        VStack(spacing: 24) {
            Spacer(minLength: 0)
            LogoImage(
                url: track.logo,
                title: track.title,
                accent: AutoBridgeDesign.accentRadio,
                cornerRadius: 22
            )
            .frame(width: 220, height: 220)
            VStack(spacing: 6) {
                Text(track.title)
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                if !track.subtitle.isEmpty {
                    Text(track.subtitle)
                        .font(.footnote)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }
            progress
            transport
            Spacer(minLength: 0)
        }
        .padding(24)
    }

    @ViewBuilder
    private var progress: some View {
        if playback.isLive || playback.duration <= 0 {
            Text("● LIVE")
                .font(.caption.weight(.bold))
                .foregroundStyle(AutoBridgeDesign.danger)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(AutoBridgeDesign.danger.opacity(0.14), in: Capsule())
        } else {
            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { scrubbing ? scrubPosition : playback.position },
                        set: { scrubPosition = $0 }
                    ),
                    in: 0...max(playback.duration, 1),
                    onEditingChanged: { editing in
                        scrubbing = editing
                        if !editing { playback.seek(to: scrubPosition) }
                    }
                )
                .tint(AutoBridgeDesign.accent)
                HStack {
                    Text(clock(scrubbing ? scrubPosition : playback.position))
                    Spacer()
                    Text(clock(playback.duration))
                }
                .font(.caption2.monospacedDigit())
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
    }

    private var transport: some View {
        HStack(spacing: 28) {
            Button {
                playback.previous()
            } label: {
                Image(systemName: "backward.end.fill").font(.title2)
            }
            .disabled(playback.queue.count < 2)
            Button {
                playback.toggle()
            } label: {
                Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title)
                    .frame(width: 68, height: 68)
                    .background(AutoBridgeDesign.accent.opacity(0.2), in: Circle())
            }
            Button {
                playback.next()
            } label: {
                Image(systemName: "forward.end.fill").font(.title2)
            }
            .disabled(playback.queue.count < 2)
        }
        .buttonStyle(.plain)
        .foregroundStyle(AutoBridgeDesign.primaryText)
    }

    private func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }
}
