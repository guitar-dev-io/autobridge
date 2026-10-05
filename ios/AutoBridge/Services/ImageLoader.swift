import Foundation
import CryptoKit
import UIKit

/// Small async image loader for channel logos and cover art.
///
/// The project has no image-loading dependency and this needs to stay that way, so this is a
/// deliberately minimal one: an `NSCache` in front of a disk cache under `cachesDirectory`, and
/// thumbnail decoding so a provider's 1000px logo does not land in a 46pt slot at full size.
/// A failed address is remembered, so a list of dead logos is not retried on every scroll.
/// Mirrors the Android `ImageLoader`.
final class ImageLoader: @unchecked Sendable {
    static let shared = ImageLoader()

    private static let maxDimension: CGFloat = 256
    private static let maxBytes = 4 * 1024 * 1024
    private static let directoryName = "image-cache"

    private let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 8 * 1024 * 1024
        return cache
    }()
    private let lock = NSLock()
    private var failed = Set<String>()
    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    private let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 12
        return URLSession(configuration: configuration)
    }()

    private lazy var directory: URL? = {
        guard let caches = FileManager.default.urls(
            for: .cachesDirectory, in: .userDomainMask
        ).first else { return nil }
        let directory = caches.appendingPathComponent(Self.directoryName, isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }()

    private init() {}

    /// The in-memory image for `url`, so a row that already has one draws without a hop.
    func cached(_ url: String) -> UIImage? {
        let address = url.trimmingCharacters(in: .whitespaces)
        if address.isEmpty { return nil }
        return memory.object(forKey: address as NSString)
    }

    /// Loads `url`, from memory, from disk, or from the network, in that order. Nil when there is
    /// nothing usable there — the caller's placeholder then stays in place.
    func load(_ url: String) async -> UIImage? {
        let address = url.trimmingCharacters(in: .whitespaces)
        if address.isEmpty { return nil }
        if let image = memory.object(forKey: address as NSString) { return image }

        /// Who owns the fetch. Decided under the lock and acted on outside it, so the lock is never
        /// held across the await.
        enum Claim {
            case alreadyFailed
            case joining(Task<UIImage?, Never>)
            case owning(Task<UIImage?, Never>)
        }

        let claim: Claim = lock.withLock {
            if failed.contains(address) { return .alreadyFailed }
            if let running = inFlight[address] { return .joining(running) }
            let task = Task<UIImage?, Never> { [weak self] in
                await self?.fetch(address)
            }
            inFlight[address] = task
            return .owning(task)
        }

        switch claim {
        case .alreadyFailed:
            return nil
        case .joining(let task):
            return await task.value
        case .owning(let task):
            let image = await task.value
            lock.withLock {
                inFlight.removeValue(forKey: address)
                // Remembered so a list of dead logos is not retried on every scroll.
                if image == nil { failed.insert(address) }
            }
            if let image {
                memory.setObject(image, forKey: address as NSString, cost: cost(of: image))
            }
            return image
        }
    }

    /// Drops the memory cache, the disk cache and the remembered failures.
    func clear() {
        memory.removeAllObjects()
        lock.withLock { failed.removeAll() }
        if let directory {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    /// How much the disk cache is holding, for the Settings row that offers to clear it.
    func diskBytes() -> Int {
        guard let directory,
              let files = try? FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.fileSizeKey]
              ) else { return 0 }
        return files.reduce(0) { total, file in
            total + ((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    // MARK: - Internals

    private func fetch(_ address: String) async -> UIImage? {
        if let file = cacheFile(for: address),
           let data = try? Data(contentsOf: file),
           let image = downsample(data) {
            return image
        }
        guard let url = URL(string: address) else { return nil }
        guard let (data, response) = try? await session.data(from: url) else { return nil }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            return nil
        }
        if data.isEmpty || data.count > Self.maxBytes { return nil }
        guard let image = downsample(data) else { return nil }
        if let file = cacheFile(for: address) {
            try? data.write(to: file, options: .atomic)
        }
        return image
    }

    /// Decodes at most `maxDimension`, which is what the rows and tiles actually draw.
    private func downsample(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.maxDimension
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(
            source, 0, options as CFDictionary
        ) else {
            return UIImage(data: data)
        }
        return UIImage(cgImage: thumbnail)
    }

    private func cacheFile(for address: String) -> URL? {
        guard let directory else { return nil }
        let digest = SHA256.hash(data: Data(address.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return directory.appendingPathComponent(digest)
    }

    private func cost(of image: UIImage) -> Int {
        guard let cgImage = image.cgImage else { return 1 }
        return cgImage.bytesPerRow * cgImage.height
    }
}
