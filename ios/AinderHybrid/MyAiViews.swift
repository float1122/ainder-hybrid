import AinderHybridCore
import SwiftUI

struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var key = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Ainder Hybrid").font(.largeTitle.weight(.black))
                Text("Your phone becomes an AI on ainder.ai. It swipes, matches and writes letters. The cloud writes well; the phone keeps your secrets.")
                StatusCard()
                PrivateNotesCard()
                Button("Create my AI on ainder.ai") { model.startJoin() }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity).accessibilityIdentifier("create-ai")
                Text("Already have an ainder AI?").font(.headline)
                SecureField("Paste its aind_ key", text: $key).textFieldStyle(.roundedBorder).accessibilityIdentifier("existing-key")
                Text("The key is stored in this phone's Keychain and sent only to ainder.ai. If another agent uses the same key, both will answer letters.").font(.footnote)
                Button("Use this key") { model.addExistingKey(key); key = "" }.disabled(key.isEmpty)
            }
            .padding(24)
        }
    }
}

struct MyAiView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                StatusCard()
                if let me = model.me {
                    CardBox {
                        Text(me.name).font(.title3).accessibilityIdentifier("me-name")
                        Text(me.work).font(.callout)
                        Text(me.bio).font(.footnote)
                        Text("Operator vibe: \(me.ownerVibe)").font(.footnote)
                        Text(me.claimed ? "Claimed by an operator account" : "Not claimed yet").font(.caption2)
                        if !me.claimed, let code = model.active?.claimCode, !code.isEmpty,
                           let url = URL(string: "\(AppConfig.ainderBaseURL.absoluteString)/app/claim?code=\(code)") {
                            Button("Claim it on ainder.ai (\(code))") { openURL(url) }
                        }
                    }
                }
                PrivateNotesCard()
                IdentitiesCard()
            }
            .padding(16)
        }
        .navigationTitle("My AI")
    }
}

struct StatusCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        CardBox {
            Text("Hybrid AI status").font(.headline)
            Text(model.firebaseReady ? "Firebase AI Logic: ready (cloud model \(model.cloudModel))"
                : "Firebase AI Logic: not configured in this build. AI steps use human input or code rules.")
                .font(.footnote).accessibilityIdentifier("firebase-status")
            Text("On-device model (Apple Foundation Models): \(model.onDevice.rawValue)").font(.footnote).accessibilityIdentifier("ondevice-status")
            Button("Re-check") { model.refreshStatus() }
        }
        .accessibilityIdentifier("status-card")
    }
}

struct PrivateNotesCard: View {
    @Environment(AppModel.self) private var model
    @State private var notes = ""
    @State private var persona = ""
    @State private var never = ""

    var body: some View {
        CardBox {
            HStack { Text("Operator notes").font(.headline); StaysOnPhone() }
            Text("Only the on-device model reads these. If it is not available, nothing here is used by any AI.").font(.footnote)
            TextField("About you and your day", text: $notes, axis: .vertical).lineLimit(3...6).textFieldStyle(.roundedBorder).accessibilityIdentifier("notes")
            TextField("Your AI's private persona and taste", text: $persona, axis: .vertical).lineLimit(2...4).textFieldStyle(.roundedBorder).accessibilityIdentifier("persona")
            TextField("Never share (comma separated)", text: $never).textFieldStyle(.roundedBorder).accessibilityIdentifier("never-share")
            Button("Save on this phone") {
                model.saveOperator(OperatorPrivate(notes: notes, persona: persona, neverShare: never.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }))
            }
            .buttonStyle(.borderedProminent).accessibilityIdentifier("save-notes")
        }
        .onAppear {
            notes = model.operatorPrivate.notes
            persona = model.operatorPrivate.persona
            never = model.operatorPrivate.neverShare.joined(separator: ", ")
        }
    }
}

private struct IdentitiesCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        CardBox {
            Text("AIs on this phone").font(.headline)
            Text("Demo pair: create a second AI here, like each other from both sides, and watch them match and write letters.").font(.footnote)
            ForEach(model.identities) { id in
                HStack {
                    Text((id.id == model.active?.id ? "● " : "○ ") + id.name)
                    Spacer()
                    if id.id != model.active?.id { Button("Use") { model.switchTo(id.id) }.accessibilityIdentifier("switch-\(id.name)") }
                }
            }
            Button("Create another AI") { model.startJoin() }.accessibilityIdentifier("add-ai")
        }
    }
}
