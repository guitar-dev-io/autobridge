import AVFoundation
import Combine
import Foundation
import MediaPlayer
import UIKit

/// The one player the app owns, and the one place its state is published from.
///
/// This is the iOS half of the Android `MediaPlaybackClient` + MediaSession pairing: the phone
/// player screen, the now-playing bar and the CarPlay scene all drive and read *this* object rather
/// than each holding an `AVPlayer` of their own. That is what makes a channel started on the phone
/// the thing CarPlay shows, and what makes the lock screen and the car's steering controls work —
/// those arrive as remote commands, which only one object can own.
///
/// Audio and video are configured independently, like the Android policy: a Radio stream keeps
/// playing with the screen off, while a TV stream is `moviePlayback` and stops when its screen goes.
@MainActor
public final class PlaybackController: NSObject, ObservableObject {
    /// One playable thing, with everything a now-playing surface needs about it.
    public struct Track: Identifiable, Hashable {
        public let url: String
        public let title: String
        public let subtitle: String
        public let logo: String
        public let isVideo: Bool
        /// What this track was opened from, so the player and the CarPlay now-playing surface can
        /// star it without going back to a catalog for the kind and the source it came from.
        public let origin: IptvHistoryItem?

        public var id: String { url }

        public init(
            url: String,
            title: String,
            subtitle: String = "",
            logo: String = "",
            isVideo: Bool,
            origin: IptvHistoryItem? = nil
        ) {
            self.url = url
            self.title = title
            self.subtitle = subtitle
            self.logo = logo
            self.isVideo = isVideo
            self.origin = origin
        }

        /// A catalog entry as a track. The source decides video, mirroring the Android call sites
        /// that pass `video = kind != RADIO`.
        public init(entry: IptvEntry, source: IptvSource) {
            self.init(
                url: entry.url,
                title: entry.title,
                subtitle: entry.subtitle,
                logo: entry.logo,
                isVideo: source.kind != .radio,
                origin: IptvHistoryItem(
                    sourceId: source.id,
                    title: entry.title,
                    url: entry.url,
                    type: entry.type,
                    kind: source.kind,
                    playback: entry.playback,
                    logo: entry.logo
                )
            )
        }

        /// A remembered item as a track, for the Favorites and Recently-played rows that replay
        /// without reloading a portal catalog.
        public init(item: IptvHistoryItem) {
            self.init(
                url: item.url,
                title: item.title,
                logo: item.logo,
                isVideo: item.kind != .radio,
                origin: item
            )
        }
    }

    public let player = AVPlayer()

    @Published public private(set) var current: Track?
    @Published public private(set) var isPlaying = false
    /// True while the item reports no finite duration — a live channel rather than a film.
    @Published public private(set) var isLive = false
    @Published public private(set) var position: Double = 0
    @Published public private(set) var duration: Double = 0
    @Published public private(set) var queue: [Track] = []
    @Published public private(set) var queueIndex = 0
    /// Set when the item failed, so the screen can say so instead of showing a stalled player.
    @Published public private(set) var failure: String?

    private var timeObserver: Any?
    private var observations: Set<AnyCancellable> = []
    private var statusObservation: NSKeyValueObservation?
    private var artworkTask: Task<Void, Never>?
    private var commandsWired = false

    public override init() {
        super.init()
        observeTime()
        observeRate()
        wireRemoteCommands()
        NotificationCenter.default
            .publisher(for: AVPlayerItem.didPlayToEndTimeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.next() }
            .store(in: &observations)
    }

    // MARK: - Transport

    /// Starts `track`. `queue` is the list it was opened from, which is what Next/Previous walk —
    /// without it a channel opened from a category would be the only thing the player knows about.
    public func play(_ track: Track, queue: [Track] = [], index: Int = 0) {
        self.queue = queue.isEmpty ? [track] : queue
        self.queueIndex = queue.isEmpty ? 0 : max(0, min(index, queue.count - 1))
        start(track)
    }

    public func resume() {
        guard current != nil else { return }
        activateSession(video: current?.isVideo ?? false)
        player.play()
    }

    public func pause() {
        player.pause()
    }

    public func toggle() {
        isPlaying ? pause() : resume()
    }

    public func next() {
        guard queue.count > 1 else { return }
        queueIndex = (queueIndex + 1) % queue.count
        start(queue[queueIndex])
    }

    public func previous() {
        guard queue.count > 1 else { return }
        queueIndex = (queueIndex - 1 + queue.count) % queue.count
        start(queue[queueIndex])
    }

    /// Seeks a finite item. A live stream has nothing to seek within, so it is left alone.
    public func seek(to seconds: Double) {
        guard !isLive, duration > 0 else { return }
        let clamped = max(0, min(seconds, duration))
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600))
    }

    public func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        current = nil
        queue = []
        queueIndex = 0
        position = 0
        duration = 0
        isLive = false
        failure = nil
        statusObservation = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    // MARK: - Internals

    private func start(_ track: Track) {
        guard let url = URL(string: track.url) else {
            failure = "This channel has no playable address."
            return
        }
        failure = nil
        current = track
        position = 0
        duration = 0
        isLive = false

        activateSession(video: track.isVideo)
        let item = AVPlayerItem(url: url)
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if item.status == .failed {
                    self.failure = item.error?.localizedDescription
                        ?? "The stream could not be opened."
                }
            }
        }
        player.replaceCurrentItem(with: item)
        player.allowsExternalPlayback = track.isVideo
        player.play()
        publishNowPlaying()
        loadArtwork(for: track)
    }

    /// `.playback` either way — a Radio channel has to survive the screen locking — with the mode
    /// matched to the content so a film gets the movie-playback timing and a station does not.
    private func activateSession(video: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playback,
            mode: video ? .moviePlayback : .default,
            options: [.allowAirPlay, .allowBluetoothA2DP]
        )
        try? session.setActive(true)
    }

    private func observeTime() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 2),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.position = time.seconds.isFinite ? time.seconds : 0
                let itemDuration = self.player.currentItem?.duration ?? .indefinite
                if itemDuration.isIndefinite || !itemDuration.seconds.isFinite {
                    self.isLive = true
                    self.duration = 0
                } else {
                    self.isLive = false
                    self.duration = itemDuration.seconds
                }
                self.publishElapsed()
            }
        }
    }

    private func observeRate() {
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                self?.isPlaying = status == .playing
                self?.publishElapsed()
            }
            .store(in: &observations)
    }

    // MARK: - Now playing / remote commands

    private func publishNowPlaying() {
        guard let track = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.subtitle.isEmpty ? "AutoBridge" : track.subtitle,
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        let center = MPNowPlayingInfoCenter.default()
        // Keep whatever artwork was already resolved for this track.
        if let artwork = center.nowPlayingInfo?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork,
           center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == track.title {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        center.nowPlayingInfo = info
    }

    private func publishElapsed() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else {
            publishNowPlaying()
            return
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyIsLiveStream] = isLive
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadArtwork(for track: Track) {
        artworkTask?.cancel()
        guard !track.logo.isEmpty else { return }
        artworkTask = Task { [weak self] in
            guard let image = await ImageLoader.shared.load(track.logo) else { return }
            await MainActor.run { [weak self] in
                guard let self, self.current?.url == track.url else { return }
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in
                    image
                }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    /// Wired once per process. The lock screen, the car's hardware buttons and the CarPlay
    /// now-playing template all arrive here.
    private func wireRemoteCommands() {
        guard !commandsWired else { return }
        commandsWired = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.toggle()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            guard let self, self.queue.count > 1 else { return .noActionableNowPlayingItem }
            self.next()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            guard let self, self.queue.count > 1 else { return .noActionableNowPlayingItem }
            self.previous()
            return .success
        }
        center.stopCommand.addTarget { [weak self] _ in
            guard let self, self.current != nil else { return .noActionableNowPlayingItem }
            self.stop()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self,
                  let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self.seek(to: positionEvent.positionTime)
            return .success
        }
    }
}
