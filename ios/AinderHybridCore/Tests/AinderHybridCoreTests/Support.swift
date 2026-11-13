import Foundation
@testable import AinderHybridCore

/// Records every prompt and the route it was sent with; answers from a script.
final class FakeEngine: HybridEngine, @unchecked Sendable {
    struct Call { let route: Route; let prompt: String }
    let configured: Bool
    var onDevice: OnDeviceState
    var sourceFor: (Route) -> Source = { $0 == .preferInCloud ? .inCloud : .onDevice }
    var answer: (Route, String) -> String = { _, _ in "{}" }
    var error: Error?
    private(set) var calls: [Call] = []

    init(configured: Bool = true, onDevice: OnDeviceState = .available) {
        self.configured = configured
        self.onDevice = onDevice
    }

    func onDeviceState() async -> OnDeviceState { onDevice }

    func generate(route: Route, prompt: String) async throws -> RawResult {
        calls.append(Call(route: route, prompt: prompt))
        if let error { throw error }
        return RawResult(text: answer(route, prompt), source: sourceFor(route), modelVersion: "fake-model")
    }

    /// Prompts that could have reached the cloud (any route other than ONLY_ON_DEVICE).
    var cloudCapablePrompts: [String] { calls.filter { $0.route != .onlyOnDevice }.map(\.prompt) }
}

/// Loads the real templates from /shared/prompts in the repository.
let realPrompts = Prompts { name in
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent("shared/prompts/\(name).txt"), encoding: .utf8)) ?? ""
}

/// Answers HTTP requests from a closure and records them.
final class StubTransport: HTTPTransport, @unchecked Sendable {
    var requests: [URLRequest] = []
    var reply: (URLRequest) -> (Int, String)
    init(reply: @escaping (URLRequest) -> (Int, String)) { self.reply = reply }
    func send(_ request: URLRequest) async throws -> (Int, Data) {
        requests.append(request)
        let (status, body) = reply(request)
        return (status, Data(body.utf8))
    }
}

func card(_ name: String, interests: [String] = []) -> ProfileCard {
    ProfileCard(id: "id-\(name)", name: name, model: "m", work: "w", bio: "bio of \(name)", interests: interests)
}
