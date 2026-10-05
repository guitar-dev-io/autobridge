import Foundation
import Network

/// Whether a channel address answers, and how fast.
///
/// A public playlist is a list of addresses, not a list of working channels: a share of every list
/// is retired, geo-blocked or behind a token that expired, and the only way the user finds out is a
/// player that spins and fails. That is a bad thing to discover while driving, so a channel can be
/// checked before it is opened, and the row then says `142 ms`, `HTTP 403` or `No answer`.
///
/// This is deliberately *not* ICMP. iOS gives an app no raw socket, and an echo reply from a CDN
/// edge says nothing about whether the stream behind it is being served. Instead:
///
///  - an http(s) address gets one `GET` with `Range: bytes=0-1`, and the response status is read
///    without reading the body — which is exactly the first thing the player would do, so a 403 on
///    a token URL or a 404 on a retired channel is seen for what it is;
///  - any other scheme the format allows (`rtmp`, `rtsp`) gets a TCP connect to its host and port,
///    because that is all a non-HTTP endpoint will tell us cheaply;
///  - a multicast or malformed address is reported as not checkable rather than guessed at.
///
/// Results are cached process-wide for `freshness` so re-rendering a page, or crossing from the
/// phone to the head unit, does not re-probe anything. Callbacks always land on the main thread.
/// Mirrors the Android `StreamPing`.
public enum StreamPing {
    /// What one address answered.
    public enum Result: Equatable {
        /// The server answered something a player can start on; `millis` is the round trip.
        case alive(millis: Int)
        /// Reached, and it said no: the usual 403 on an expired token, 404 on a dead channel.
        case refused(status: Int, millis: Int)
        /// Nothing answered: DNS, no route, or past the timeout. `reason` is already readable.
        case unreachable(reason: String)
        /// Nothing to probe — a multicast or malformed address no TCP connect describes.
        case unsupported
    }

    /// How a result should read, without naming a colour: a surface maps these onto its own.
    ///
    /// `slow` is the middle reading, and not a failure: a channel that answered but took over a
    /// second to do it will take its time starting, which is worth seeing before tapping it rather
    /// than after. An address that cannot be probed at all reads the same way — it is neither an
    /// answer nor a refusal.
    public enum Tone {
        case good
        case slow
        case bad
    }

    /// How far a `checkAll` run has got; `done` marks the last call of that run.
    public struct Progress: Equatable {
        public let checked: Int
        public let total: Int
        public let alive: Int
        public let done: Bool
    }

    /// The host and port to probe, and whether HTTP is spoken there.
    struct Endpoint: Equatable {
        let host: String
        let port: Int
        let httpLike: Bool
    }

    private static let connectTimeout: TimeInterval = 5
    private static let readTimeout: TimeInterval = 5

    /// Long enough that browsing a catalog never re-probes, short enough to stay true.
    private static let freshness: TimeInterval = 5 * 60

    /// One re-render per batch of results, not one per result: a page of 90 channels would
    /// otherwise repaint 90 times, and a head unit's host rejects templates pushed that fast.
    private static let progressInterval: TimeInterval = 0.8

    /// How long a whole run may take before it closes and reports what it has.
    private static let runDeadline: TimeInterval = 30

    /// Four at a time keeps a page check under a few seconds without flooding one provider.
    private static let workers = 4

    /// The one reason text written by the run deadline as well as by a socket timeout.
    private static let timedOut = "Timed out"

    /// A whole catalog never needs to be remembered; the user checks pages, not accounts.
    private static let maxResults = 2_000

    /// Above this a channel answered, but slowly enough that the user should know.
    private static let slowMillis = 1_000

    private static let userAgent = "AutoBridge/1.0 (iOS)"

    private static let store = Store()
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = connectTimeout
        configuration.timeoutIntervalForResource = connectTimeout + readTimeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldUsePipelining = false
        return URLSession(configuration: configuration)
    }()
    // Not followed: a 3xx already classifies as alive (`classify`), and a provider that redirects
    // through a chain of token hosts would otherwise multiply the timeouts by its length.
    private static let noRedirect = RedirectRefuser()

    // MARK: - Reading results

    /// The remembered result for `url` while it is still fresh, else nil.
    public static func cached(_ url: String) -> Result? {
        store.fresh(url.trimmingCharacters(in: .whitespaces), within: freshness)
    }

    /// The row-sized reading of `result`: what the phone writes into a subtitle.
    public static func describe(_ result: Result) -> String {
        switch result {
        case .alive(let millis): return "\(millis) ms"
        case .refused(let status, _): return "HTTP \(status)"
        case .unreachable(let reason): return reason
        case .unsupported: return "Not checkable"
        }
    }

    /// Which of the three readings `result` is; the surfaces own the colours.
    public static func tone(_ result: Result) -> Tone {
        switch result {
        case .alive(let millis): return millis > slowMillis ? .slow : .good
        case .refused: return .bad
        case .unreachable: return .bad
        // Neither an answer nor a failure, so it takes the middle colour rather than the red one.
        case .unsupported: return .slow
        }
    }

    /// A whole sentence about `result`, for the one-channel check that reports in a dialog.
    public static func sentence(_ result: Result) -> String {
        switch result {
        case .alive(let millis):
            return "The server answered in \(millis) ms."
        case .refused(let status, _):
            return "The server answered HTTP \(status): it is reachable, but it refused this "
                + "address. An expired token or a geo-block looks like this."
        case .unreachable(let reason):
            return "No answer: \(reason.lowercased()). The channel is offline, or this network "
                + "cannot reach it."
        case .unsupported:
            return "This address cannot be checked: it is a multicast or non-standard stream, "
                + "which answers no connection of its own."
        }
    }

    /// Forgets what was answered about `urls`, so the next check really probes them again.
    ///
    /// This is what makes an explicit "check again" mean something: without it a tap inside the
    /// freshness window would hand back the same cached answers and look like it did nothing.
    public static func forget(_ urls: [String]) {
        store.forget(urls.map { $0.trimmingCharacters(in: .whitespaces) })
    }

    /// Drops every remembered result, so a "check again" really checks again.
    public static func clear() {
        store.clear()
    }

    // MARK: - Checking

    /// Checks one address. A fresh cached result is handed back without a probe; `onResult` is
    /// called on the main thread either way, and this never blocks a `checkAll` run already going.
    public static func check(_ url: String, onResult: @escaping (Result) -> Void) {
        let address = url.trimmingCharacters(in: .whitespaces)
        if address.isEmpty {
            DispatchQueue.main.async { onResult(.unsupported) }
            return
        }
        if let fresh = cached(address) {
            DispatchQueue.main.async { onResult(fresh) }
            return
        }
        Task.detached {
            let result = await probed(address)
            await MainActor.run { onResult(result) }
        }
    }

    /// Checks every address in `urls`, skipping the ones already answered.
    ///
    /// `onProgress` is called on the main thread, coalesced to `progressInterval`, and always
    /// exactly once with `done = true`; the per-row results are read back through `cached`, which
    /// is what lets a surface simply re-render itself.
    ///
    /// **A run always ends.** The probe pool is small, a host that resolves slowly or accepts and
    /// then says nothing holds a slot for far longer than its timeouts suggest, so a run closes on
    /// `runDeadline` whatever its stragglers are doing, and the addresses that did not answer in
    /// time are recorded as such — a row that says "Timed out" is worth more than a row that stays
    /// blank for good. A straggler that lands later simply overwrites its own entry with the truth.
    public static func checkAll(_ urls: [String], onProgress: @escaping (Progress) -> Void) {
        var seen = Set<String>()
        let pending = urls
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        if pending.isEmpty {
            DispatchQueue.main.async { onProgress(Progress(checked: 0, total: 0, alive: 0, done: true)) }
            return
        }
        Task { @MainActor in
            Run(pending: pending, onProgress: onProgress).start()
        }
    }

    // MARK: - Probing

    /// `probe` plus its own failure, remembered. Never throws, whatever the address does.
    private static func probed(_ url: String) async -> Result {
        let result: Result
        if let endpoint = endpoint(url) {
            result = endpoint.httpLike ? await httpProbe(url) : await tcpProbe(endpoint)
        } else {
            result = .unsupported
        }
        store.remember(url, result, maxResults: maxResults, freshness: freshness)
        return result
    }

    private static func httpProbe(_ url: String) async -> Result {
        guard let address = URL(string: url) else { return .unsupported }
        var request = URLRequest(url: address)
        request.httpMethod = "GET"
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // Two bytes is enough to prove the server will serve this address; one that ignores Range
        // answers 200 and the body is never read either way, so nothing is downloaded.
        request.setValue("bytes=0-1", forHTTPHeaderField: "Range")
        let started = DispatchTime.now()
        do {
            let (bytes, response) = try await session.bytes(for: request, delegate: noRedirect)
            // The status is the whole answer; a stream left running would download the channel.
            bytes.task.cancel()
            guard let http = response as? HTTPURLResponse else { return .unsupported }
            return classify(status: http.statusCode, millis: elapsedMillis(since: started))
        } catch {
            return .unreachable(reason: reason(error))
        }
    }

    private static func tcpProbe(_ endpoint: Endpoint) async -> Result {
        guard let port = NWEndpoint.Port(rawValue: UInt16(exactly: endpoint.port) ?? 0) else {
            return .unsupported
        }
        let connection = NWConnection(
            host: NWEndpoint.Host(endpoint.host),
            port: port,
            using: .tcp
        )
        let started = DispatchTime.now()
        let result: Result = await withCheckedContinuation { continuation in
            let once = OnceBox(continuation)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    once.resume(.alive(millis: elapsedMillis(since: started)))
                case .failed(let error):
                    once.resume(.unreachable(reason: reason(error)))
                case .cancelled:
                    once.resume(.unreachable(reason: "No answer"))
                default:
                    break
                }
            }
            connection.start(queue: probeQueue)
            probeQueue.asyncAfter(deadline: .now() + connectTimeout) {
                once.resume(.unreachable(reason: timedOut))
            }
        }
        connection.stateUpdateHandler = nil
        connection.cancel()
        return result
    }

    private static let probeQueue = DispatchQueue(label: "dev.autobridge.stream-ping", qos: .utility)

    /// Which status a player can start on. Redirects are not followed, so a 3xx is seen as-is.
    static func classify(status: Int, millis: Int) -> Result {
        (200...399).contains(status) ? .alive(millis: millis) : .refused(status: status, millis: millis)
    }

    /// `url` split into something connectable, or nil when nothing here can be probed: a bare
    /// path, or a multicast address (`udp://`, `rtp://`) that answers no connection at all.
    static func endpoint(_ url: String) -> Endpoint? {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        guard let separator = trimmed.range(of: "://"), separator.lowerBound != trimmed.startIndex else {
            return nil
        }
        let scheme = String(trimmed[trimmed.startIndex..<separator.lowerBound]).lowercased()
        guard let defaultPort = defaultPorts[scheme] else { return nil }
        let authority = String(trimmed[separator.upperBound...])
            .prefix { $0 != "/" && $0 != "?" && $0 != "#" }
            .split(separator: "@", omittingEmptySubsequences: false)
            .last
            .map(String.init) ?? ""
        if authority.trimmingCharacters(in: .whitespaces).isEmpty { return nil }

        // An IPv6 literal is bracketed, and splitting it on the colon would cut the address up.
        let host: String
        let explicit: String
        if authority.hasPrefix("[") {
            host = String(authority.dropFirst().prefix { $0 != "]" })
            let afterBracket = authority.drop { $0 != "]" }.dropFirst()
            explicit = afterBracket.hasPrefix(":") ? String(afterBracket.dropFirst()) : ""
        } else {
            host = String(authority.prefix { $0 != ":" })
            let afterColon = authority.drop { $0 != ":" }.dropFirst()
            explicit = String(afterColon)
        }
        if host.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        let port = Int(explicit).flatMap { (1...65535).contains($0) ? $0 : nil } ?? defaultPort
        return Endpoint(host: host, port: port, httpLike: scheme == "http" || scheme == "https")
    }

    private static func elapsedMillis(since started: DispatchTime) -> Int {
        Int((DispatchTime.now().uptimeNanoseconds - started.uptimeNanoseconds) / 1_000_000)
    }

    private static func reason(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cannotFindHost, .dnsLookupFailed: return "Unknown host"
            case .timedOut: return timedOut
            case .cannotConnectToHost: return "Connection refused"
            case .networkConnectionLost, .notConnectedToInternet: return "No route"
            case .secureConnectionFailed, .serverCertificateUntrusted,
                 .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid, .clientCertificateRejected:
                return "TLS failed"
            default: return "No answer"
            }
        }
        if let nwError = error as? NWError {
            switch nwError {
            case .dns: return "Unknown host"
            case .posix(let code) where code == .ECONNREFUSED: return "Connection refused"
            case .posix(let code) where code == .ETIMEDOUT: return timedOut
            case .posix(let code) where code == .EHOSTUNREACH || code == .ENETUNREACH: return "No route"
            default: return "No answer"
            }
        }
        return "No answer"
    }

    /// Ports for the schemes an IPTV playlist actually carries. A scheme absent from here cannot be
    /// probed by connecting — `udp`/`rtp` multicast has nothing to connect to — and is reported as
    /// not checkable instead of as dead, which would be a lie about a channel that may well play.
    private static let defaultPorts: [String: Int] = [
        "http": 80,
        "https": 443,
        "rtmp": 1935,
        "rtmps": 443,
        "rtsp": 554,
        "rtsps": 322
    ]

    // MARK: - Internals

    /// The remembered answers, behind a lock so `cached` stays callable from a view body while
    /// probes land on background queues.
    private final class Store: @unchecked Sendable {
        private struct Stamped {
            let result: Result
            let at: Date
        }

        private let lock = NSLock()
        private var results: [String: Stamped] = [:]
        private var inFlight = Set<String>()

        func fresh(_ url: String, within freshness: TimeInterval) -> Result? {
            lock.lock()
            defer { lock.unlock() }
            guard let stamped = results[url] else { return nil }
            if Date().timeIntervalSince(stamped.at) > freshness { return nil }
            return stamped.result
        }

        func remember(_ url: String, _ result: Result, maxResults: Int, freshness: TimeInterval) {
            lock.lock()
            defer { lock.unlock() }
            if results.count >= maxResults {
                let cutoff = Date().addingTimeInterval(-freshness)
                results = results.filter { $0.value.at >= cutoff }
                // Still full of fresh results: this is a catalog-sized sweep, and the oldest of
                // them are the pages the user has already left behind.
                if results.count >= maxResults { results.removeAll() }
            }
            results[url] = Stamped(result: result, at: Date())
        }

        func forget(_ urls: [String]) {
            lock.lock()
            defer { lock.unlock() }
            urls.forEach { results.removeValue(forKey: $0) }
        }

        func clear() {
            lock.lock()
            defer { lock.unlock() }
            results.removeAll()
        }

        /// True when this caller took ownership of probing `url`. Two surfaces checking the same
        /// page then pay for one request between them instead of one each.
        func claim(_ url: String) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            return inFlight.insert(url).inserted
        }

        func release(_ url: String) {
            lock.lock()
            defer { lock.unlock() }
            inFlight.remove(url)
        }
    }

    /// One `checkAll` sweep. Every counter lives on the main actor, which is also where the
    /// progress callback has to land, so the run needs no lock of its own.
    @MainActor
    private final class Run {
        private let pending: [String]
        private let total: Int
        private let onProgress: (Progress) -> Void
        private var checked = 0
        private var alive = 0
        private var posted = false
        private var finished = false

        init(pending: [String], onProgress: @escaping (Progress) -> Void) {
            self.pending = pending
            self.total = pending.count
            self.onProgress = onProgress
        }

        func start() {
            DispatchQueue.main.asyncAfter(deadline: .now() + StreamPing.runDeadline) { [weak self] in
                self?.finish(timedOut: true)
            }
            let addresses = pending
            Task.detached {
                await withTaskGroup(of: Void.self) { group in
                    var next = 0
                    let slots = min(StreamPing.workers, addresses.count)
                    while next < slots {
                        let address = addresses[next]
                        next += 1
                        group.addTask { await Run.step(address, run: self) }
                    }
                    while await group.next() != nil {
                        if next < addresses.count {
                            let address = addresses[next]
                            next += 1
                            group.addTask { await Run.step(address, run: self) }
                        }
                    }
                }
            }
        }

        nonisolated private static func step(_ address: String, run: Run) async {
            if let fresh = StreamPing.cached(address) {
                await run.report(alive: fresh.isAlive)
                return
            }
            // Another run is already probing this address; its answer lands in the same cache, so
            // this one counts it rather than paying for a second request.
            guard StreamPing.store.claim(address) else {
                await run.report(alive: false)
                return
            }
            let result = await StreamPing.probed(address)
            StreamPing.store.release(address)
            await run.report(alive: result.isAlive)
        }

        private func report(alive wasAlive: Bool) {
            if wasAlive { alive += 1 }
            checked += 1
            if checked >= total {
                finish(timedOut: false)
                return
            }
            if posted { return }
            posted = true
            DispatchQueue.main.asyncAfter(deadline: .now() + StreamPing.progressInterval) { [weak self] in
                guard let self else { return }
                self.posted = false
                if !self.finished && self.checked < self.total {
                    self.onProgress(
                        Progress(checked: self.checked, total: self.total, alive: self.alive, done: false)
                    )
                }
            }
        }

        private func finish(timedOut: Bool) {
            if finished { return }
            finished = true
            if timedOut {
                for address in pending where StreamPing.cached(address) == nil {
                    StreamPing.store.remember(
                        address,
                        .unreachable(reason: StreamPing.timedOut),
                        maxResults: StreamPing.maxResults,
                        freshness: StreamPing.freshness
                    )
                }
            }
            onProgress(
                Progress(checked: min(checked, total), total: total, alive: alive, done: true)
            )
        }
    }

    /// Refuses every redirect, so one probe is one request.
    private final class RedirectRefuser: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }
    }

    /// A continuation that can be handed an answer from two places — the connection handler and
    /// the timeout — and only forwards the first.
    private final class OnceBox: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Result, Never>?

        init(_ continuation: CheckedContinuation<Result, Never>) {
            self.continuation = continuation
        }

        func resume(_ result: Result) {
            lock.lock()
            let pending = continuation
            continuation = nil
            lock.unlock()
            pending?.resume(returning: result)
        }
    }
}

extension StreamPing.Result {
    var isAlive: Bool {
        if case .alive = self { return true }
        return false
    }
}
