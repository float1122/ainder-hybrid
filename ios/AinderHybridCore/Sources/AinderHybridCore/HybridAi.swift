import Foundation

/// Raw model output before routing checks.
public struct RawResult: Sendable {
    public let text: String
    public let source: Source
    public let modelVersion: String?
    public init(text: String, source: Source, modelVersion: String?) {
        self.text = text
        self.source = source
        self.modelVersion = modelVersion
    }
}

/// Seam between the routing logic and the Firebase AI Logic SDK (faked in unit tests).
public protocol HybridEngine: Sendable {
    var configured: Bool { get }
    func onDeviceState() async -> OnDeviceState
    func generate(route: Route, prompt: String) async throws -> RawResult
}

public struct AiResult: Sendable {
    public let text: String
    public let source: Source
    public let ms: Int
    public let cloudChars: Int
}

public enum AiOutcome: Sendable {
    case done(AiResult)
    case useFallback(Fallback, reason: String)

    public var result: AiResult? {
        if case let .done(r) = self { return r }
        return nil
    }

    public var fallbackReason: String {
        if case let .useFallback(_, reason) = self { return reason }
        return "Model output unreadable"
    }
}

public struct LogEntry: Identifiable, Sendable {
    public let id = UUID()
    public let at: Date
    public let task: AiTask
    public let source: Source
    public let ms: Int
    public let cloudChars: Int
    public let note: String
}

/// In-memory record of every AI call: task, where it ran, time, characters sent to the cloud.
public final class HybridLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [LogEntry] = []
    private let max: Int
    /// Called after every change (the app mirrors entries into its observable model).
    public var onChange: (@Sendable ([LogEntry]) -> Void)?

    public init(max: Int = 200) { self.max = max }

    public var entries: [LogEntry] { lock.withLock { items } }

    public func add(_ entry: LogEntry) {
        let snapshot: [LogEntry] = lock.withLock {
            items = Array(([entry] + items).prefix(max))
            return items
        }
        onChange?(snapshot)
    }

    public func cloudChars(for privacy: Privacy) -> Int {
        entries.filter { $0.task.privacy == privacy }.reduce(0) { $0 + $1.cloudChars }
    }
}

/// Runs an AiTask through RoutingPolicy and the HybridEngine, and records it in HybridLog.
/// Never throws for model problems: failures turn into `.useFallback`.
public final class HybridAi: Sendable {
    private let engine: any HybridEngine
    public let log: HybridLog
    private let clock: @Sendable () -> Date

    public init(engine: any HybridEngine, log: HybridLog, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.engine = engine
        self.log = log
        self.clock = clock
    }

    public var configured: Bool { engine.configured }

    public func onDeviceState() async -> OnDeviceState {
        engine.configured ? await engine.onDeviceState() : .unknown
    }

    public func run(_ task: AiTask, prompt: String) async -> AiOutcome {
        let start = clock()
        let decision = RoutingPolicy.decide(task, aiConfigured: engine.configured, onDevice: await onDeviceState())
        let route: Route
        switch decision {
        case let .useFallback(fb, reason): return fallback(task, start, fb, reason)
        case let .run(r): route = r
        }
        let raw: RawResult
        do {
            raw = try await engine.generate(route: route, prompt: prompt)
        } catch is CancellationError {
            return fallback(task, start, task.fallback, "Cancelled")
        } catch {
            return fallback(task, start, task.fallback, "Model error: \(type(of: error))")
        }
        guard RoutingPolicy.accept(task, source: raw.source) else {
            let leaked = RoutingPolicy.cloudChars(raw.source, prompt: prompt)
            return fallback(task, start, task.fallback, "Discarded: private task answered by \(raw.source.badge)", cloudChars: leaked)
        }
        let ms = elapsedMs(since: start)
        let chars = RoutingPolicy.cloudChars(raw.source, prompt: prompt)
        log.add(LogEntry(at: start, task: task, source: raw.source, ms: ms, cloudChars: chars, note: raw.modelVersion ?? ""))
        return .done(AiResult(text: raw.text, source: raw.source, ms: ms, cloudChars: chars))
    }

    private func fallback(_ task: AiTask, _ start: Date, _ fb: Fallback, _ reason: String, cloudChars: Int = 0) -> AiOutcome {
        log.add(LogEntry(at: start, task: task, source: .noAI, ms: elapsedMs(since: start), cloudChars: cloudChars, note: "\(fb.rawValue): \(reason)"))
        return .useFallback(fb, reason: reason)
    }

    private func elapsedMs(since start: Date) -> Int { max(0, Int(clock().timeIntervalSince(start) * 1000)) }
}
