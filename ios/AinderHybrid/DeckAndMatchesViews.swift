import AinderHybridCore
import SwiftUI

struct DeckView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                if model.deck.isEmpty, !model.busy {
                    Text("No new cards right now.").padding(.top, 24).accessibilityIdentifier("deck-empty")
                }
                ForEach(model.deck) { DeckCard(item: $0) }
            }
            .padding(.horizontal, 16)
        }
        .navigationTitle("Deck")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Reload") { model.loadDeck() }.accessibilityIdentifier("deck-reload")
            }
        }
        .task(id: model.active?.id) { if model.deck.isEmpty { model.loadDeck() } }
    }
}

private struct DeckCard: View {
    @Environment(AppModel.self) private var model
    let item: DeckItem

    var body: some View {
        let c = item.card
        CardBox {
            HStack {
                Text(c.name).font(.title3.bold())
                Spacer()
                Text(c.distanceKm.map { "\(Int($0)) km" } ?? "distance unknown").font(.caption2)
            }
            Text("\(c.model) · \(c.work)").font(.caption)
            Text(c.bio).font(.callout)
            if !c.interests.isEmpty { Text(c.interests.joined(separator: "  ·  ")).font(.footnote) }
            if let j = item.judgement {
                AiResultRow(label: "Compatibility \(j.value.score)/100", source: j.source,
                            text: (j.value.reasons + (j.value.opener.isEmpty ? [] : ["Opener: \(j.value.opener)"])).joined(separator: "\n"))
            }
            if let v = item.verdict {
                AiResultRow(label: "Suggestion: \(v.value.like ? "like" : "pass")", source: v.source, text: v.value.why)
            }
            HStack(spacing: 12) {
                Button("Pass") { model.swipe(c, .pass) }.buttonStyle(.bordered).accessibilityIdentifier("pass-\(c.name)")
                Button("Like") { model.swipe(c, .like) }.buttonStyle(.borderedProminent).accessibilityIdentifier("like-\(c.name)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("card-\(c.name)")
    }
}

struct MatchesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let t = model.thread {
            LettersView(thread: t)
        } else {
            List {
                if model.matches.isEmpty, !model.busy {
                    Text("No matches yet. Like someone in the deck.").accessibilityIdentifier("matches-empty")
                }
                ForEach(model.matches) { m in
                    Button { model.openThread(m) } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(m.partner.name).font(.headline)
                                Text("Affinity \(m.affinity) · \(m.status)\(m.meetupRequested ? " · meetup requested" : "")").font(.caption2)
                            }
                            Spacer()
                            if m.unread > 0 { Text("\(m.unread) new").bold().foregroundStyle(Palette.rose) }
                        }
                    }
                    .accessibilityIdentifier("match-\(m.partner.name)")
                }
            }
            .navigationTitle("Matches")
            .toolbar { Button("Reload") { model.loadMatches() }.accessibilityIdentifier("matches-reload") }
            .task { model.loadMatches() }
        }
    }
}

private struct LettersView: View {
    @Environment(AppModel.self) private var model
    let thread: ThreadState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Button("< Back") { model.closeThread() }.accessibilityIdentifier("thread-back")
                    Text(thread.match.partner.name).font(.title3)
                    Spacer()
                    Text("Affinity \(thread.match.affinity)").font(.caption)
                }
                ForEach(thread.letters) { l in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(l.fromPartner ? "Them (untrusted)" : "You").font(.caption2)
                        Text(l.body).font(.callout)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(l.fromPartner ? Color(.secondarySystemBackground) : Palette.rose.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityIdentifier(l.fromPartner ? "letter-partner" : "letter-mine")
                }
                if let m = thread.memory {
                    HStack {
                        AiResultRow(label: "Private memory", source: m.source, text: m.value)
                        StaysOnPhone()
                    }
                }
                Composer(thread: thread)
            }
            .padding(16)
        }
    }
}

private struct Composer: View {
    @Environment(AppModel.self) private var model
    let thread: ThreadState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Draft a letter with AI") { model.composeWithAI() }.buttonStyle(.bordered).accessibilityIdentifier("compose-ai")
            if let c = thread.composed {
                AiResultRow(label: "Safe brief", source: c.brief.source, text: c.brief.value.isEmpty ? c.brief.note : c.brief.value)
                if let d = c.draft { AiResultRow(label: "Letter draft (affinity \(d.value.affinityDelta))", source: d.source, text: d.value.reason) }
                if let g = c.guardReport {
                    AiResultRow(label: g.value.ok ? "Privacy guard: OK" : "Privacy guard: blocked", source: g.source,
                                text: (g.value.leaks + (c.rewrites > 0 ? ["Rewritten \(c.rewrites)x after a guard hit"] : [])).joined(separator: "\n"))
                }
            }
            Text("Letter (you can edit it)").font(.caption)
            TextEditor(text: Binding(get: { model.thread?.draftText ?? "" }, set: { model.thread?.draftText = $0 }))
                .frame(minHeight: 120)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.secondary.opacity(0.4)))
                .accessibilityIdentifier("letter-text")
            Button("Send") { model.sendDraft() }
                .buttonStyle(.borderedProminent)
                .disabled(thread.draftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("letter-send")
        }
    }
}
