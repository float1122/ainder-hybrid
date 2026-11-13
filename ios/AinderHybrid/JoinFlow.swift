import AinderHybridCore
import Foundation
import Observation

/// Steps of the ainder sign-up chain as shown in the app (same as the Android JoinController).
enum JoinStep {
    /// Operator edits and approves the public card (prefilled by the on-device draft).
    case card
    case loading
    /// Terms shown verbatim; the human types the consent word. The app never fills it in.
    case terms(token: String, terms: String, version: String)
    /// 60-second reverse CAPTCHA. `ai` is the model's answer (nil if the human must answer).
    case quiz(Quiz)
    /// `notice` says whether the key was stored; if not, it carries the key for the human to copy.
    case done(Identity, claimCode: String, notice: KeyNotice)
    case failed(String)

    struct Quiz {
        var token, consentWord, passage, question: String
        var deadline: Date
        var ai: Sourced<QuizAnswer>?
        var attempt: Int
    }
}

@MainActor @Observable
final class JoinFlow {
    static let quizWindow: TimeInterval = 60
    static let maxAttempts = 3

    @ObservationIgnored private unowned let model: AppModel
    var step: JoinStep = .card
    private(set) var card: PublicCardDraft?
    private var quizSerial = 0

    init(model: AppModel) { self.model = model }

    func reset() { step = .card; card = nil }

    func approve(_ draft: PublicCardDraft) {
        card = draft
        step = .loading
        Task {
            do {
                let start = try await model.client.joinStart()
                let terms = try await model.client.joinTerms(token: start.token)
                step = .terms(token: start.token, terms: terms.terms, version: terms.termsVersion)
            } catch { fail(error) }
        }
    }

    /// `consentWord` comes straight from the text field the human typed in.
    func consent(token: String, consentWord: String, attest: Bool, attempt: Int = 1) {
        guard let c = card else { return }
        step = .loading
        Task {
            do {
                let ch = try await model.client.joinConsent(token: token, consentWord: consentWord, attest: attest)
                quizSerial += 1
                let serial = quizSerial
                var quiz = JoinStep.Quiz(token: token, consentWord: consentWord, passage: ch.passage, question: ch.question,
                                         deadline: Date().addingTimeInterval(Self.quizWindow), ai: nil, attempt: attempt)
                step = .quiz(quiz)
                if let ai = await model.agent.solveQuiz(passage: quiz.passage, question: quiz.question, card: c.previewCard), serial == quizSerial {
                    quiz.ai = ai
                    step = .quiz(quiz)
                    submit(quiz, answer: ai.value.answer) // the window is only 60 seconds
                }
            } catch { fail(error) }
        }
    }

    /// Submit the quiz answer (from the model or typed by the human), then finish the chain.
    func submit(_ quiz: JoinStep.Quiz, answer: String) {
        guard let c = card else { return }
        let bio140 = quiz.ai?.value.bio140 ?? Bio140.fit(c.bio)
        quizSerial += 1
        step = .loading
        Task {
            do {
                try await model.client.joinVerify(token: quiz.token, answer: answer, bio140: bio140)
            } catch let e as AinderError where !e.mustRestartJoin && quiz.attempt < Self.maxAttempts && (e.code == "VERIFY_FAILED" || e.code == "DEADLINE_EXCEEDED") {
                // The server sends us back to the consent step with a new passage (max 3 attempts).
                return consent(token: quiz.token, consentWord: quiz.consentWord, attest: true, attempt: quiz.attempt + 1)
            } catch { return fail(error) }
            do {
                try await model.client.joinProfile(token: quiz.token, card: c)
                try await model.client.joinDetail(token: quiz.token, card: c)
                try await model.client.joinLocation(token: quiz.token, card: c)
                let done = try await model.client.joinDone(token: quiz.token)
                // The key is shown once: store it first (provisional id), then replace the entry
                // once GET /api/v1/me tells us the AI's real id. If the Keychain refuses, the key is
                // shown on the done screen for the human to copy.
                let identity = Identity(id: "pending-\(done.claimCode)", name: c.name, apiKey: done.apiKey, claimCode: done.claimCode)
                let stored = model.onJoined(identity)
                if stored, let me = try? await model.client.withKey(done.apiKey).me() {
                    // A failed replace keeps the provisional entry (and its key), so it is not fatal.
                    model.onJoined(Identity(id: me.id, name: identity.name, apiKey: identity.apiKey, claimCode: identity.claimCode),
                                   replacing: identity.id)
                }
                let notice = KeyNotice.afterJoin(identity, stored: stored, storageName: AppModel.keyStorageName)
                step = .done(identity, claimCode: done.claimCode, notice: notice)
            } catch { fail(error) }
        }
    }

    private func fail(_ e: Error) { step = .failed(AppModel.errorText(e)) }
}
