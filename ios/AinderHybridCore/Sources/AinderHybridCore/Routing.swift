import Foundation

/// Where a task may run. Mirrors the Firebase AI Logic hybrid modes the app uses.
public enum Route: String, Sendable {
    case preferOnDevice = "PREFER_ON_DEVICE"
    case onlyOnDevice = "ONLY_ON_DEVICE"
    case preferInCloud = "PREFER_IN_CLOUD"
}

/// Whether the task's prompt contains data that must never leave the phone.
public enum Privacy: Sendable { case `public`, `private` }

/// What the app does when no model can run the task. None of these use inference.
public enum Fallback: String, Sendable {
    /// The human types or picks the result.
    case human = "HUMAN"
    /// A deterministic code rule decides (no model).
    case codeRule = "CODE_RULE"
    /// The step is skipped; nothing is lost (data can be re-read from ainder).
    case skip = "SKIP"
}

/// Every AI task in the app (letters A–H in the README). Same table as the Android app.
/// PRIVATE tasks see operator notes, persona or memory and are ONLY_ON_DEVICE: if the on-device
/// model is missing they fall back to a human or a code rule, never to the cloud.
public enum AiTask: String, CaseIterable, Sendable {
    case quiz, card, brief, judge, rerank, letter, guardCheck, memory

    public var label: String {
        switch self {
        case .quiz: "Sign-up quiz"
        case .card: "Public card draft"
        case .brief: "Safe brief"
        case .judge: "Compatibility"
        case .rerank: "Like or pass"
        case .letter: "Letter"
        case .guardCheck: "Privacy guard"
        case .memory: "Memory"
        }
    }

    public var privacy: Privacy {
        switch self {
        case .quiz, .judge, .letter: .public
        case .card, .brief, .rerank, .guardCheck, .memory: .private
        }
    }

    public var route: Route {
        switch self {
        case .quiz: .preferOnDevice
        case .judge, .letter: .preferInCloud
        case .card, .brief, .rerank, .guardCheck, .memory: .onlyOnDevice
        }
    }

    public var fallback: Fallback {
        switch self {
        case .quiz, .card, .brief, .letter: .human
        case .judge, .rerank, .guardCheck: .codeRule
        case .memory: .skip
        }
    }

    /// File name in /shared/prompts (without .txt).
    public var promptName: String { self == .guardCheck ? "guard" : rawValue }
}

/// State of the on-device model (Apple Foundation Models availability, as seen by Firebase AI Logic).
public enum OnDeviceState: String, CaseIterable, Sendable {
    case available, notEligible, notEnabled, notReady, unknown
}

/// Which engine produced a result. noAI = human input or a code rule.
public enum Source: String, Sendable {
    case onDevice = "ON_DEVICE", inCloud = "IN_CLOUD", noAI = "NO_AI"

    public var badge: String {
        switch self {
        case .onDevice: "On-device"
        case .inCloud: "Cloud"
        case .noAI: "No AI"
        }
    }
}

public enum Decision: Equatable, Sendable {
    case run(Route)
    case useFallback(Fallback, reason: String)
}

/// Pure routing rules: the privacy boundary of the app. Unit tested in RoutingPolicyTests.
public enum RoutingPolicy {
    public static func decide(_ task: AiTask, aiConfigured: Bool, onDevice: OnDeviceState) -> Decision {
        precondition(task.privacy == .public || task.route == .onlyOnDevice, "\(task) would leak private data")
        guard aiConfigured else { return .useFallback(task.fallback, reason: "Firebase AI Logic is not configured in this build") }
        switch task.privacy {
        case .private:
            return onDevice == .available
                ? .run(.onlyOnDevice)
                : .useFallback(task.fallback, reason: "On-device model is \(onDevice.rawValue); private data stays on this phone")
        case .public:
            return .run(task.route)
        }
    }

    /// Second line of defence: a PRIVATE task's result is accepted only if it was produced on the device.
    public static func accept(_ task: AiTask, source: Source) -> Bool {
        task.privacy == .public || source == .onDevice
    }

    /// Characters that left the phone for this call (shown in the Hybrid log).
    public static func cloudChars(_ source: Source, prompt: String) -> Int {
        source == .inCloud ? prompt.count : 0
    }
}
