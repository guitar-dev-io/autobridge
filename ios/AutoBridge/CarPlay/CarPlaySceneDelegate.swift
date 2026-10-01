import Foundation
import UIKit
import CarPlay
import AVFoundation

/// CarPlay audio scene skeleton.
///
/// This exposes a browsable list of IPTV sources and their channels on the car head unit using the
/// CarPlay **audio** app templates (`CPListTemplate`), and plays the selected stream through the
/// shared `AVAudioSession`. It is intentionally audio-only: CarPlay has no arbitrary-mirroring or
/// video category, so this is the Radio/audio surface, not the phone's full player.
///
/// Running this on real hardware requires the `com.apple.developer.carplay-audio` entitlement,
/// which Apple grants on request. Without it the scene simply never activates; the phone app works
/// regardless. The scene is wired in Info.plist under `UIApplicationSceneManifest` →
/// `CPTemplateApplicationSceneSessionRoleApplication`.
@objc(CarPlaySceneDelegate)
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private let sourceStore = CarPlaySourceProvider()
    private var player: AVPlayer?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        interfaceController.setRootTemplate(makeRootTemplate(), animated: true, completion: nil)
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
        player?.pause()
        player = nil
    }

    // MARK: - Templates

    private func makeRootTemplate() -> CPListTemplate {
        let sections = sourceStore.sources(kind: .radio).map { source -> CPListSection in
            let item = CPListItem(text: source.name, detailText: source.url)
            item.handler = { [weak self] _, completion in
                self?.openSource(source)
                completion()
            }
            return CPListSection(items: [item])
        }
        let template = CPListTemplate(title: "AutoBridge Radio", sections: sections)
        template.emptyViewTitleVariants = ["No sources"]
        template.emptyViewSubtitleVariants = ["Add a Radio source on your phone first."]
        return template
    }

    private func openSource(_ source: IptvSource) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let loader = IptvCatalogLoader()
            guard let data = try? await loader.load(source) else { return }
            let items = data.entries.prefix(200).map { entry -> CPListItem in
                let item = CPListItem(text: entry.title, detailText: entry.subtitle.isEmpty ? nil : entry.subtitle)
                item.handler = { [weak self] _, completion in
                    self?.play(entry)
                    completion()
                }
                return item
            }
            let template = CPListTemplate(title: source.name, sections: [CPListSection(items: Array(items))])
            self.interfaceController?.pushTemplate(template, animated: true, completion: nil)
        }
    }

    private func play(_ entry: IptvEntry) {
        guard !entry.isWebPage, let url = URL(string: entry.url) else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
        let player = AVPlayer(url: url)
        player.play()
        self.player = player
    }
}

/// Reads the persisted sources for CarPlay without binding to the SwiftUI environment. CarPlay runs
/// in its own scene, so it reads the same `UserDefaults`-backed store the phone app writes.
@MainActor
private final class CarPlaySourceProvider {
    private let store = IptvSourceStore()
    func sources(kind: IptvKind) -> [IptvSource] { store.sources(kind: kind) }
}
