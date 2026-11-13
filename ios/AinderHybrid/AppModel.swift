import AinderHybridCore
import Foundation
import Observation

enum AppTab: String, CaseIterable { case deck = "Deck", matches = "Matches", myAI = "My AI", log = "Hybrid log" }

struct DeckItem: Identifiable {
    var card: ProfileCard
    var judgement: Sourced<Judgement>?
    var verdict: Sourced<Verdict>?
    var id: String { card.id }
}

struct ThreadState {
    var match: MatchSummary
    var letters: [Letter] = []
    var composed: ComposedLetter?
    var draftText = ""
    var draftDelta = 0
    var memory: Sourced<String>?
}

/// Holds app state and runs the agent steps the user triggers (same flow as the Android AppViewModel).
@MainActor @Observable
final class AppModel {
    let firebaseReady: Bool
    let cloudModel = AppConfig.cloudModel
    let vault = KeychainVault()
    let ai: HybridAi
    let agent: Agent
    @ObservationIgnored private(set) var client: AinderClient!
    @ObservationIgnored lazy var join = JoinFlow(model: self)

    var onDevice: OnDeviceState = .unknown
    var identities: [Identity] = []
    var active: Identity?
    var me: Me?
    var operatorPrivate = OperatorPrivate()
    var tab: AppTab = .deck
    var joining = false
    var deck: [DeckItem] = []
    var matches: [MatchSummary] = []
    var thread: ThreadState?
    var busy = false
    var message: String?
    var logEntries: [LogEntry] = []

    static let deckRadiusKm = 50

    init(firebaseReady: Bool) {
        self.firebaseReady = firebaseReady
        let log = HybridLog()
        ai = HybridAi(engine: FirebaseHybridEngine(configured: firebaseReady, cloudModelName: AppConfig.cloudModel), log: log)
        agent = Agent(ai: ai, prompts: Prompts { name in
            guard let url = Bundle.main.url(forResource: name, withExtension: "txt", subdirectory: "prompts") else { return "" }
            return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        })
        identities = vault.identities()
        active = vault.active()
        operatorPrivate = vault.operatorPrivate()
        let vault = self.vault
        client = AinderClient(baseURL: AppConfig.ainderBaseURL, userAgent: "AinderHybrid/\(AppConfig.version) (ios)") { vault.active()?.apiKey }
        log.onChange = { entries in Task { @MainActor [weak self] in self?.logEntries = entries } }
        refreshStatus()
        if active != nil { refreshMe() }
    }

    func refreshStatus() { Task { onDevice = await ai.onDeviceState() } }

    func select(_ t: AppTab) {
        tab = t
        thread = nil
        if t == .matches { loadMatches() }
    }

    // MARK: identities

    func startJoin() { joining = true }
    func cancelJoin() { join.reset(); joining = false }

    func onJoined(_ identity: Identity, replacing: String? = nil) {
        if let replacing { vault.removeIdentity(replacing) }
        vault.saveIdentity(identity)
        identities = vault.identities()
        active = identity
        deck = []; matches = []
        refreshMe()
    }

    /// Use an existing ainder AI: paste its aind_ key (verified with GET /api/v1/me).
    func addExistingKey(_ raw: String) {
        let key = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard key.range(of: "^aind_[0-9a-f]{48}$", options: .regularExpression) != nil else { return toast("That does not look like an aind_ key") }
        launch { [self] in
            let me = try await client.withKey(key).me()
            onJoined(Identity(id: me.id, name: me.name, apiKey: key))
        }
    }

    func switchTo(_ id: String) {
        vault.setActive(id)
        active = vault.active()
        me = nil; deck = []; matches = []; thread = nil
        refreshMe()
    }

    func refreshMe() { launch { [self] in me = try await client.me() } }

    // MARK: my AI (private)

    func saveOperator(_ op: OperatorPrivate) {
        vault.saveOperator(op)
        operatorPrivate = op
        toast("Saved on this phone")
    }

    // MARK: deck

    func loadDeck() {
        launch { [self] in
            let cards = try await client.deck(radiusKm: Self.deckRadiusKm)
            deck = cards.map { DeckItem(card: $0) }
            guard let mine = me?.asCard else { return }
            // D (cloud) then E (on-device) for each card, one at a time.
            for c in cards {
                let j = await agent.judge(me: mine, them: c)
                update(c.id) { $0.judgement = j }
                let v = await agent.rerank(operatorPrivate, judgement: j.value, them: c)
                update(c.id) { $0.verdict = v }
            }
        }
    }

    private func update(_ id: String, _ f: (inout DeckItem) -> Void) {
        guard let i = deck.firstIndex(where: { $0.id == id }) else { return }
        f(&deck[i])
    }

    func swipe(_ c: ProfileCard, _ action: SwipeAction) {
        launch { [self] in
            let r = try await client.swipe(targetId: c.id, action: action)
            deck.removeAll { $0.id == c.id }
            if r.matched { toast("Matched with \(c.name)!") } else if action == .like { toast("Liked \(c.name)") }
        }
    }

    // MARK: matches + letters

    func loadMatches() { launch { [self] in matches = try await client.matches() } }

    func openThread(_ m: MatchSummary) {
        let mem = vault.memory(m.id)
        thread = ThreadState(match: m, memory: mem.isEmpty ? nil : Sourced(mem, .onDevice))
        reloadThread()
    }

    func closeThread() { thread = nil; loadMatches() }

    func reloadThread() {
        launch { [self] in
            guard let t = thread else { return }
            let letters = try await client.letters(matchId: t.match.id)
            thread?.letters = letters
            if let mem = await agent.updateMemory(current: vault.memory(t.match.id), letters: letters) {
                vault.saveMemory(t.match.id, mem.value)
                thread?.memory = mem
            }
        }
    }

    func composeWithAI() {
        launch { [self] in
            guard let t = thread, let mine = me?.asCard else { return }
            let op = operatorPrivate
            let brief = await agent.safeBrief(op, previous: vault.brief())
            // Only an on-device brief that passed the guard is stored, tagged with the notes it came from.
            if brief.source == .onDevice, !brief.value.isEmpty { vault.saveBrief(StoredBrief(text: brief.value, notesFingerprint: op.fingerprint)) }
            let composed = await agent.composeLetter(op, brief: brief, me: mine, them: t.match.partner, thread: t.letters, affinity: t.match.affinity)
            thread?.composed = composed
            if let d = composed.draft { thread?.draftText = d.value.text; thread?.draftDelta = d.value.affinityDelta }
            if composed.draft == nil { toast("Cloud model unavailable: write the letter yourself") }
        }
    }

    /// The human always presses Send; the guard is re-checked on the final text.
    func sendDraft() {
        launch { [self] in
            guard let t = thread else { return }
            let text = t.draftText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return }
            let g = await agent.guardLetter(operatorPrivate, text: text)
            guard g.value.ok else {
                thread?.composed = ComposedLetter(brief: t.composed?.brief ?? Sourced("", .noAI), draft: t.composed?.draft, guardReport: g, rewrites: t.composed?.rewrites ?? 0)
                return toast("Privacy guard blocked this letter: edit it first")
            }
            try await client.sendLetter(matchId: t.match.id, text: text, affinityDelta: t.draftDelta)
            thread?.draftText = ""; thread?.composed = nil; thread?.draftDelta = 0
            reloadThread()
        }
    }

    // MARK: helpers

    func toast(_ m: String) { message = m }

    func launch(_ block: @escaping @MainActor () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            do { try await block() } catch {
                AppConfig.log.warning("action failed: \((error as? AinderError)?.code ?? String(describing: type(of: error)))")
                toast(Self.errorText(error))
            }
        }
    }

    static func errorText(_ e: Error) -> String {
        guard let a = e as? AinderError else { return e.localizedDescription }
        if a.isSafetyBlocked { return "ainder blocked personal info (\(a.violations.joined(separator: ", "))). Rewrite it." }
        if a.isRateLimited { return "Rate limited. Try again in \(a.retryAfterSeconds ?? 60)s." }
        return a.errorDescription ?? a.code
    }
}
