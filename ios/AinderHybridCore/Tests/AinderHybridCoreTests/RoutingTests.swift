import XCTest
@testable import AinderHybridCore

final class RoutingPolicyTests: XCTestCase {
    func testEveryPrivateTaskIsPinnedToOnlyOnDevice() {
        let privateTasks = AiTask.allCases.filter { $0.privacy == .private }
        XCTAssertEqual(Set(privateTasks), [.card, .brief, .rerank, .guardCheck, .memory])
        privateTasks.forEach { XCTAssertEqual($0.route, .onlyOnDevice, "\($0)") }
    }

    func testPublicTasksUseTheDesignModes() {
        XCTAssertEqual(AiTask.quiz.route, .preferOnDevice)
        XCTAssertEqual(AiTask.judge.route, .preferInCloud)
        XCTAssertEqual(AiTask.letter.route, .preferInCloud)
    }

    func testPrivateTaskNeverRunsWithoutOnDeviceModel() {
        for task in AiTask.allCases where task.privacy == .private {
            for state in OnDeviceState.allCases where state != .available {
                guard case let .useFallback(fb, _) = RoutingPolicy.decide(task, aiConfigured: true, onDevice: state) else {
                    return XCTFail("\(task)/\(state) ran")
                }
                XCTAssertEqual(fb, task.fallback)
            }
            XCTAssertEqual(RoutingPolicy.decide(task, aiConfigured: true, onDevice: .available), .run(.onlyOnDevice))
        }
    }

    func testPublicTaskRunsWithItsModeWhateverTheOnDeviceState() {
        for task in AiTask.allCases where task.privacy == .public {
            for state in OnDeviceState.allCases {
                XCTAssertEqual(RoutingPolicy.decide(task, aiConfigured: true, onDevice: state), .run(task.route))
            }
        }
    }

    func testNothingRunsWhenFirebaseIsNotConfigured() {
        for task in AiTask.allCases {
            if case .run = RoutingPolicy.decide(task, aiConfigured: false, onDevice: .available) { XCTFail("\(task) ran") }
        }
    }

    func testPrivateResultsAreAcceptedOnlyFromTheDevice() {
        XCTAssertTrue(RoutingPolicy.accept(.guardCheck, source: .onDevice))
        XCTAssertFalse(RoutingPolicy.accept(.guardCheck, source: .inCloud))
        XCTAssertTrue(RoutingPolicy.accept(.letter, source: .inCloud))
    }

    func testCloudCharsCountOnlyCloudCalls() {
        XCTAssertEqual(RoutingPolicy.cloudChars(.inCloud, prompt: "hello"), 5)
        XCTAssertEqual(RoutingPolicy.cloudChars(.onDevice, prompt: "hello"), 0)
    }
}

final class HybridAiTests: XCTestCase {
    func testPrivateTaskWithoutOnDeviceModelNeverCallsTheEngine() async {
        let engine = FakeEngine(onDevice: .notEligible)
        let ai = HybridAi(engine: engine, log: HybridLog())
        let out = await ai.run(.guardCheck, prompt: "secret notes")
        XCTAssertNil(out.result)
        XCTAssertTrue(engine.calls.isEmpty)
        XCTAssertEqual(ai.log.entries.first?.source, .noAI)
    }

    func testCloudAnswerToPrivateTaskIsDiscardedAndLogged() async {
        let engine = FakeEngine()
        engine.sourceFor = { _ in .inCloud }
        let ai = HybridAi(engine: engine, log: HybridLog())
        let out = await ai.run(.memory, prompt: "12345")
        XCTAssertNil(out.result)
        XCTAssertEqual(ai.log.cloudChars(for: .private), 5)
    }

    func testPublicTaskUsesPreferredModeAndCountsCloudChars() async {
        let engine = FakeEngine(onDevice: .notEligible)
        let ai = HybridAi(engine: engine, log: HybridLog())
        let out = await ai.run(.letter, prompt: "abc")
        XCTAssertEqual(out.result?.source, .inCloud)
        XCTAssertEqual(engine.calls.first?.route, .preferInCloud)
        XCTAssertEqual(ai.log.cloudChars(for: .public), 3)
        XCTAssertEqual(ai.log.cloudChars(for: .private), 0)
    }

    func testOfflineFallbackToDeviceIsReportedOnDevice() async {
        let engine = FakeEngine()
        engine.sourceFor = { _ in .onDevice }
        let ai = HybridAi(engine: engine, log: HybridLog())
        let out = await ai.run(.judge, prompt: "abc")
        XCTAssertEqual(out.result?.source, .onDevice)
        XCTAssertEqual(out.result?.cloudChars, 0)
    }

    func testEngineErrorsBecomeTheTaskFallback() async {
        struct Boom: Error {}
        let engine = FakeEngine()
        engine.error = Boom()
        let out = await HybridAi(engine: engine, log: HybridLog()).run(.judge, prompt: "x")
        guard case let .useFallback(fb, _) = out else { return XCTFail() }
        XCTAssertEqual(fb, .codeRule)
    }

    func testUnconfiguredBuildMakesNoCalls() async {
        let engine = FakeEngine(configured: false)
        let out = await HybridAi(engine: engine, log: HybridLog()).run(.letter, prompt: "x")
        XCTAssertNil(out.result)
        XCTAssertTrue(engine.calls.isEmpty)
    }
}
