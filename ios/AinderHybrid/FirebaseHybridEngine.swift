import AinderHybridCore
import FirebaseAILogic
import Foundation

/// The only place the app performs inference: Firebase AI Logic hybrid inference.
/// Cloud = Gemini Developer API backend; on-device = Apple Foundation Models through Firebase AI
/// Logic's `systemModel()` provider. One single-turn session per call (no chat history).
///
/// Route mapping (see README):
/// - ONLY_ON_DEVICE   → `systemModel()` alone: no cloud model in the session at all.
/// - PREFER_ON_DEVICE → `hybridModel(primary: systemModel, secondary: gemini)`.
/// - PREFER_IN_CLOUD  → `hybridModel(primary: gemini, secondary: systemModel)`.
final class FirebaseHybridEngine: HybridEngine {
    /// `GenerateContentResponse.modelVersion` reported for Apple Foundation Models answers.
    static let onDeviceModelVersion = "apple-foundation-models-system-language-model"

    let configured: Bool
    private let cloudModelName: String

    init(configured: Bool, cloudModelName: String) {
        self.configured = configured
        self.cloudModelName = cloudModelName
    }

    func onDeviceState() async -> OnDeviceState {
        switch FirebaseAI.SystemLanguageModel.systemModel().availability {
        case .available: return .available
        case let .unavailable(reason):
            switch reason {
            case .deviceNotEligible: return .notEligible
            case .appleIntelligenceNotEnabled: return .notEnabled
            case .modelNotReady: return .notReady
            default: return .unknown
            }
        }
    }

    func generate(route: Route, prompt: String) async throws -> RawResult {
        let ai = FirebaseAI.firebaseAI(backend: .googleAI())
        let device = FirebaseAI.SystemLanguageModel.systemModel()
        let cloud = GeminiModelProvider.geminiModel(name: cloudModelName)
        let model: any LanguageModelProvider = switch route {
        case .onlyOnDevice: device
        case .preferOnDevice: HybridModelProvider.hybridModel(primary: device, secondary: cloud)
        case .preferInCloud: HybridModelProvider.hybridModel(primary: cloud, secondary: device)
        }
        let response = try await ai.generativeModelSession(model: model).respond(to: prompt)
        let version = response.rawResponse.modelVersion
        let source: Source = version == Self.onDeviceModelVersion ? .onDevice : .inCloud
        return RawResult(text: response.content, source: source, modelVersion: version)
    }
}
