import SwiftUI
import AVKit
import AVFoundation

/// The stream player. Wraps `AVPlayerViewController` so HLS/progressive streams get native
/// transport controls, PiP, and AirPlay. Configures the audio session for playback so Radio keeps
/// playing with the screen locked and so audio routes to the car over Bluetooth/CarPlay.
struct PlayerView: View {
    let entry: IptvEntry

    @EnvironmentObject private var historyStore: PlaybackHistoryStore
    @State private var player: AVPlayer?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea(edges: .bottom)
            } else {
                ProgressView().tint(.white)
            }
        }
        .navigationTitle(entry.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    historyStore.toggleFavorite(entry)
                } label: {
                    Image(systemName: historyStore.isFavorite(entry) ? "star.fill" : "star")
                }
            }
        }
        .onAppear(perform: start)
        .onDisappear(perform: stop)
    }

    private func start() {
        configureAudioSession()
        guard let url = URL(string: entry.url) else { return }
        let player = AVPlayer(url: url)
        player.allowsExternalPlayback = true
        player.play()
        self.player = player
    }

    private func stop() {
        player?.pause()
        player = nil
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback, options: [.allowAirPlay, .allowBluetoothA2DP])
        try? session.setActive(true)
    }
}
