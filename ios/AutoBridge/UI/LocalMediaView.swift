import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// On-device media: the Files section (anything the document picker can reach) and the Gallery
/// section (the photo library).
///
/// This is the iOS reading of the Android `FOLDERS` and `GALLERY` sections. Android walks
/// `MediaStore` and lists every folder on the device; iOS has no equivalent — an app sees its own
/// container and nothing else — so the system pickers stand in for the walk. Both are
/// permission-free by design: `PHPickerViewController` and `UIDocumentPickerViewController` run out
/// of process and hand back only what the user chose, so neither section asks for library access.
struct LocalMediaView: View {
    enum Mode {
        case files
        case gallery

        var title: String {
            switch self {
            case .files: return NSLocalizedString("Folders", comment: "Home section")
            case .gallery: return NSLocalizedString("Gallery", comment: "Home section")
            }
        }

        var caption: String {
            switch self {
            case .files:
                return NSLocalizedString(
                    "Pick audio or video from Files, iCloud Drive or any provider you have.",
                    comment: "Local media"
                )
            case .gallery:
                return NSLocalizedString(
                    "Pick a clip or a photo from your library.",
                    comment: "Local media"
                )
            }
        }

        var accent: Color {
            switch self {
            case .files: return AutoBridgeDesign.accentFiles
            case .gallery: return AutoBridgeDesign.accentWeb
            }
        }
    }

    let mode: Mode

    @EnvironmentObject private var playback: PlaybackController

    @State private var picking = false
    @State private var items: [LocalItem] = []
    @State private var viewingImage: LocalItem?
    @State private var problem: String?

    /// One thing the user picked this session. The copy lives in the caches directory, because a
    /// picker URL is only valid while its security scope is held and `AVPlayer` outlives that.
    struct LocalItem: Identifiable, Hashable {
        let name: String
        let url: URL
        let isImage: Bool

        var id: String { url.absoluteString }
    }

    var body: some View {
        PageShell(
            title: mode.title,
            subtitle: items.isEmpty ? mode.caption : plural(items.count, "item"),
            accent: mode.accent,
            actions: [
                PageAction(title: NSLocalizedString("Pick media", comment: "Local media")) {
                    picking = true
                }
            ]
        ) {
            LazyVStack(spacing: 10) {
                ForEach(items) { item in
                    Button {
                        open(item)
                    } label: {
                        ContentRow(
                            title: item.name,
                            subtitle: item.isImage
                                ? NSLocalizedString("Photo", comment: "Local media")
                                : NSLocalizedString("Media file", comment: "Local media"),
                            accent: mode.accent,
                            badge: item.isImage ? "◧" : "▶"
                        )
                    }
                    .buttonStyle(.plain)
                }
                if items.isEmpty {
                    EmptyState(
                        title: NSLocalizedString("Nothing picked yet", comment: "Empty state"),
                        message: mode.caption,
                        accent: mode.accent,
                        actionTitle: NSLocalizedString("Pick media", comment: "Local media"),
                        action: { picking = true }
                    )
                }
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
        .sheet(isPresented: $picking) {
            switch mode {
            case .files:
                DocumentPicker { urls in
                    picking = false
                    adopt(urls)
                }
            case .gallery:
                LibraryPicker { urls in
                    picking = false
                    adopt(urls)
                }
            }
        }
        .sheet(item: $viewingImage) { item in
            ImageViewer(item: item)
        }
        .alert(
            NSLocalizedString("Could not open that file", comment: "Local media"),
            isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })
        ) {
            Button(NSLocalizedString("OK", comment: "Dialog"), role: .cancel) {}
        } message: {
            Text(problem ?? "")
        }
    }

    private func adopt(_ urls: [URL]) {
        for url in urls {
            guard let copy = LocalMediaFiles.adopt(url) else {
                problem = url.lastPathComponent
                continue
            }
            let item = LocalItem(
                name: copy.deletingPathExtension().lastPathComponent,
                url: copy,
                isImage: LocalMediaFiles.isImage(copy)
            )
            items.removeAll { $0.url == item.url }
            items.insert(item, at: 0)
        }
        if let first = items.first, urls.count == 1 {
            open(first)
        }
    }

    private func open(_ item: LocalItem) {
        if item.isImage {
            viewingImage = item
            return
        }
        playback.play(
            PlaybackController.Track(
                url: item.url.absoluteString,
                title: item.name,
                isVideo: LocalMediaFiles.isVideo(item.url)
            )
        )
    }
}

/// Where picked files are kept, and what they are.
enum LocalMediaFiles {
    private static let imageTypes: Set<String> = ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp"]
    private static let audioTypes: Set<String> = ["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"]

    static func isImage(_ url: URL) -> Bool {
        imageTypes.contains(url.pathExtension.lowercased())
    }

    static func isVideo(_ url: URL) -> Bool {
        !isImage(url) && !audioTypes.contains(url.pathExtension.lowercased())
    }

    /// Copies a picked file into the app's caches directory and returns the copy.
    ///
    /// The picker hands back a URL that is only readable while its security scope is held, and
    /// `AVPlayer` reads long after the picker is gone, so the file is taken rather than referenced.
    static func adopt(_ url: URL) -> URL? {
        guard let directory = directory() else { return nil }
        let destination = directory.appendingPathComponent(url.lastPathComponent)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if FileManager.default.fileExists(atPath: destination.path) { return destination }
        do {
            try FileManager.default.copyItem(at: url, to: destination)
            return destination
        } catch {
            return nil
        }
    }

    private static func directory() -> URL? {
        guard let caches = FileManager.default.urls(
            for: .cachesDirectory, in: .userDomainMask
        ).first else { return nil }
        let directory = caches.appendingPathComponent("picked-media", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

/// `UIDocumentPickerViewController` for the Files section.
struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(
            forOpeningContentTypes: [.audiovisualContent, .audio, .movie, .image],
            asCopy: true
        )
        controller.allowsMultipleSelection = true
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: ([URL]) -> Void

        init(onPick: @escaping ([URL]) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            onPick(urls)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onPick([])
        }
    }
}

/// `PHPickerViewController` for the Gallery section. Out of process, so no library permission.
struct LibraryPicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.filter = .any(of: [.videos, .images])
        configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPick: ([URL]) -> Void

        init(onPick: @escaping ([URL]) -> Void) {
            self.onPick = onPick
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider,
                  let type = provider.registeredTypeIdentifiers.first else {
                onPick([])
                return
            }
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url else {
                    DispatchQueue.main.async { self.onPick([]) }
                    return
                }
                // The representation is deleted as soon as this closure returns, so it is adopted
                // here rather than handed on.
                let adopted = LocalMediaFiles.adopt(url)
                DispatchQueue.main.async { self.onPick(adopted.map { [$0] } ?? []) }
            }
        }
    }
}

/// A picked photo, shown full screen.
struct ImageViewer: View {
    let item: LocalMediaView.LocalItem

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let image = UIImage(contentsOfFile: item.url.path) {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                } else {
                    Text(NSLocalizedString("Could not read that photo.", comment: "Local media"))
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(NSLocalizedString("Done", comment: "Dialog")) { dismiss() }
                }
            }
        }
    }
}
