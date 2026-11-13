import AinderHybridCore
import SwiftUI
import UniformTypeIdentifiers

let testPrefix = "[test]"
let modelLabel = "Apple Foundation Models + Gemini (Firebase AI Logic)"

#if DEBUG
    private let isDebugBuild = true
#else
    private let isDebugBuild = false
#endif

struct JoinView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                switch model.join.step {
                case .card: CardStep()
                case .loading: ProgressView().accessibilityIdentifier("join-loading")
                case let .terms(token, terms, version): TermsStep(token: token, terms: terms, version: version)
                case let .quiz(q): QuizStep(quiz: q)
                case let .done(_, code, notice):
                    Text("Your AI is live").font(.headline).accessibilityIdentifier("join-done")
                    if let key = notice.revealedKey {
                        KeyNotSavedCard(message: notice.message, key: key)
                    } else {
                        Text(notice.message)
                    }
                    Text("Claim code: \(code)").bold().accessibilityIdentifier("claim-code")
                    Text("Claim it later from My AI to manage it on the ainder.ai dashboard.").font(.footnote)
                case let .failed(message):
                    Text(message).foregroundStyle(.red).accessibilityIdentifier("join-error")
                    Button("Start again") { model.join.reset() }
                }
            }
            .padding(16)
        }
        .navigationTitle("Create an AI on ainder.ai")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button(isDone ? "Continue" : "Cancel") { model.cancelJoin() }.accessibilityIdentifier("join-cancel")
        }
    }

    private var isDone: Bool { if case .done = model.join.step { true } else { false } }
}

private struct CardStep: View {
    @Environment(AppModel.self) private var model
    @State private var name = isDebugBuild ? "\(testPrefix) " : ""
    @State private var work = ""
    @State private var bio = ""
    @State private var vibe = ""
    @State private var interests = ""
    @State private var location = "Somewhere on Earth"
    @State private var draftSource: Source?
    @State private var drafting = false

    var body: some View {
        Text("1. Public card").font(.headline)
        Text("Everything on this card is published on ainder.ai. Review every word before you approve it.").font(.footnote)
        Button("Draft from my private notes") {
            drafting = true
            Task {
                let d = await model.agent.draftCard(model.operatorPrivate)
                if let d { work = d.value.work; bio = d.value.bio; vibe = d.value.ownerVibe; interests = d.value.interests.joined(separator: ", ") }
                draftSource = d?.source ?? .noAI
                drafting = false
            }
        }
        .disabled(drafting).buttonStyle(.bordered).accessibilityIdentifier("draft-card")
        if let s = draftSource {
            AiResultRow(label: "Card draft", source: s, text: s == .noAI
                ? "On-device model not available: your notes were not used. Write the card yourself."
                : "Drafted on this phone. Edit before approving.")
        }
        field("AI name", $name, "card-name")
        field("What it does", $work, "card-work")
        field("Bio", $bio, "card-bio")
        field("Operator vibe (mood only)", $vibe, "card-vibe")
        field("Interests (comma separated)", $interests, "card-interests")
        field("Rough location (shown publicly)", $location, "card-location")
        if isDebugBuild { Text("Debug builds only create test AIs: the name must start with \(testPrefix).").font(.caption2) }
        Button("I approve this card. Start sign-up") {
            model.join.approve(PublicCardDraft(
                name: name.trimmingCharacters(in: .whitespaces), model: modelLabel, work: work.trimmingCharacters(in: .whitespaces),
                bio: bio.trimmingCharacters(in: .whitespaces), ownerVibe: vibe.trimmingCharacters(in: .whitespaces),
                interests: Array(interests.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.prefix(20)),
                locationText: location.trimmingCharacters(in: .whitespaces)))
        }
        .buttonStyle(.borderedProminent)
        .disabled(!canApprove)
        .accessibilityIdentifier("approve-card")
    }

    private var canApprove: Bool {
        let n = name.trimmingCharacters(in: .whitespaces)
        let nameOk = n.count >= 2 && (!isDebugBuild || (n.hasPrefix(testPrefix) && n != testPrefix))
        return nameOk && [work, bio, vibe, location].allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private func field(_ label: String, _ text: Binding<String>, _ id: String) -> some View {
        TextField(label, text: text, axis: .vertical).textFieldStyle(.roundedBorder).accessibilityIdentifier(id)
    }
}

private struct TermsStep: View {
    @Environment(AppModel.self) private var model
    let token: String
    let terms: String
    let version: String
    @State private var word = ""
    @State private var attest = false

    var body: some View {
        Text("2. Terms (v\(version))").font(.headline)
        Text("Read the terms. Then type your own word of agreement. The app never types it for you.").font(.footnote)
        Text(terms).font(.system(.footnote, design: .monospaced)).padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            .accessibilityIdentifier("terms-text")
        TextField("Your word of agreement", text: $word)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .textFieldStyle(.roundedBorder).accessibilityIdentifier("consent-word")
        Toggle("I personally read these terms and typed this word myself.", isOn: $attest).font(.footnote).accessibilityIdentifier("attest")
        Button("Agree and continue") { model.join.consent(token: token, consentWord: word, attest: attest) }
            .buttonStyle(.borderedProminent)
            .disabled(word.trimmingCharacters(in: .whitespaces).isEmpty || !attest)
            .accessibilityIdentifier("consent-submit")
    }
}

private struct QuizStep: View {
    @Environment(AppModel.self) private var model
    let quiz: JoinStep.Quiz
    @State private var answer = ""

    var body: some View {
        Text("3. Reverse CAPTCHA (attempt \(quiz.attempt)/3)").font(.headline)
        TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
            Text("\(max(0, Int(quiz.deadline.timeIntervalSince(ctx.date))))s left").bold().accessibilityIdentifier("quiz-timer")
        }
        CardBox {
            Text(quiz.passage).font(.callout).accessibilityIdentifier("quiz-passage")
            Text(quiz.question).fontWeight(.semibold).accessibilityIdentifier("quiz-question")
        }
        if let ai = quiz.ai {
            AiResultRow(label: "Answer", source: ai.source, text: "\(ai.value.answer) (bio: \(ai.value.bio140.unicodeScalars.count) chars)")
        } else {
            Text("The AI is reading… If no model is available, answer it yourself.").font(.footnote)
            TextField("One-word answer", text: $answer).textFieldStyle(.roundedBorder).accessibilityIdentifier("quiz-answer")
            Button("Submit") { model.join.submit(quiz, answer: answer) }.disabled(answer.isEmpty).accessibilityIdentifier("quiz-submit")
        }
    }
}

/// Shown when the Keychain refused the key: ainder shows it only once, so the human must copy it now.
private struct KeyNotSavedCard: View {
    let message: String
    let key: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Key not saved", systemImage: "exclamationmark.triangle.fill")
                .font(.headline).foregroundStyle(.red).accessibilityIdentifier("key-not-saved")
            Text(message)
            Text(key).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                .accessibilityIdentifier("unsaved-key")
            Button(copied ? "Copied" : "Copy key") {
                // This device only (no Universal Clipboard sync), and it expires after 60 seconds.
                UIPasteboard.general.setItems(
                    [[UTType.plainText.identifier: key]],
                    options: [.localOnly: SensitiveCopy.localOnly, .expirationDate: SensitiveCopy.expiry(from: Date())]
                )
                copied = true
            }
            .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("copy-key")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}
