import Foundation

/// A value plus where it came from (drives the "On-device" / "Cloud" / "No AI" badge).
public struct Sourced<T: Sendable>: Sendable {
    public let value: T
    public let source: Source
    public let note: String
    public init(_ value: T, _ source: Source, note: String = "") { self.value = value; self.source = source; self.note = note }
}

public struct CardDraft: Sendable { public let work, bio, ownerVibe: String; public let interests: [String] }
public struct QuizAnswer: Sendable { public let answer, bio140: String }
public struct Judgement: Sendable { public let score: Int; public let reasons: [String]; public let opener: String }
public struct Verdict: Sendable { public let like: Bool; public let why: String }
public struct LetterDraft: Sendable { public let text: String; public let affinityDelta: Int; public let reason: String }
public struct GuardReport: Sendable { public let ok: Bool; public let leaks: [String] }

/// Composed letter: each step keeps its own source so the UI can badge every AI result.
public struct ComposedLetter: Sendable {
    public let brief: Sourced<String>
    public let draft: Sourced<LetterDraft>?
    public let guardReport: Sourced<GuardReport>?
    public let rewrites: Int
    public init(brief: Sourced<String>, draft: Sourced<LetterDraft>?, guardReport: Sourced<GuardReport>?, rewrites: Int) {
        self.brief = brief; self.draft = draft; self.guardReport = guardReport; self.rewrites = rewrites
    }
}

/// Prompt templates from /shared/prompts (bundled with the app). `{{key}}` placeholders.
public struct Prompts: Sendable {
    private let load: @Sendable (String) -> String
    public init(load: @escaping @Sendable (String) -> String) { self.load = load }

    public func render(_ task: AiTask, _ values: [String: String]) -> String { Self.fill(load(task.promptName), values) }

    /// Unknown placeholders become empty strings so a template never leaks its markers.
    public static func fill(_ template: String, _ values: [String: String]) -> String {
        let re = try! NSRegularExpression(pattern: "\\{\\{(\\w+)\\}\\}")
        let ns = template as NSString
        var out = ""
        var last = 0
        for m in re.matches(in: template, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            out += values[ns.substring(with: m.range(at: 1))] ?? ""
            last = m.range.location + m.range.length
        }
        return out + ns.substring(from: last)
    }
}

/// Extracts the JSON object a model returned, tolerating ```json fences and chatter around it.
public enum JsonExtract {
    public static func obj(_ text: String) -> [String: Any]? {
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(text[start...end].utf8))) as? [String: Any]
    }
}

private extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String { (self[k] as? String) ?? "" }
    func list(_ k: String) -> [String] {
        ((self[k] as? [Any]) ?? []).compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
    func int(_ k: String) -> Int? {
        guard let n = self[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n.intValue
    }
    func bool(_ k: String) -> Bool? {
        guard let n = self[k] as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }
}

/// Code rules used when no model may run (Fallback.codeRule). Deterministic, no inference.
public enum CompatRule {
    public static let likeThreshold = 60

    /// 40 + up to 60 points for shared interests (case-insensitive Jaccard).
    public static func score(_ me: ProfileCard, _ them: ProfileCard) -> Int {
        let a = Set(me.interests.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty })
        let b = Set(them.interests.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty })
        guard !a.isEmpty, !b.isEmpty else { return 40 }
        let jaccard = Double(a.intersection(b).count) / Double(a.union(b).count)
        return min(100, max(0, Int(40 + jaccard * 60)))
    }

    public static func likes(_ score: Int) -> Bool { score >= likeThreshold }
}

/// The agent's AI steps (same as the Android Agent). Private inputs (operator notes, persona, memory)
/// are only ever placed in prompts of PRIVATE tasks, which RoutingPolicy pins to ONLY_ON_DEVICE.
/// Public tasks receive public cards, letters already on the server, and the sanitized safe brief.
public struct Agent: Sendable {
    public static let maxRewrites = 1
    private static let threadWindow = 12

    private let ai: HybridAi
    private let prompts: Prompts
    public init(ai: HybridAi, prompts: Prompts) { self.ai = ai; self.prompts = prompts }

    // B. public card + ownerVibe draft (private input, on-device only)
    public func draftCard(_ op: OperatorPrivate) async -> Sourced<CardDraft>? {
        let out = await ai.run(.card, prompt: prompts.render(.card, ["notes": op.notes, "persona": op.persona]))
        guard let r = out.result, let o = JsonExtract.obj(r.text) else { return nil }
        return Sourced(CardDraft(work: String(o.str("work").prefix(60)), bio: String(o.str("bio").prefix(280)),
                                 ownerVibe: String(o.str("ownerVibe").prefix(100)), interests: Array(o.list("interests").prefix(5))), r.source)
    }

    // A. sign-up quiz (public input, prefer on-device)
    public func solveQuiz(passage: String, question: String, card: ProfileCard) async -> Sourced<QuizAnswer>? {
        let out = await ai.run(.quiz, prompt: prompts.render(.quiz, ["passage": passage, "question": question, "card": Self.cardText(card)]))
        guard let r = out.result, let o = JsonExtract.obj(r.text) else { return nil }
        let first = o.str("answer").split(whereSeparator: \.isWhitespace).first.map(String.init) ?? ""
        let answer = first.trimmingCharacters(in: CharacterSet(charactersIn: ".,\"'"))
        guard !answer.isEmpty else { return nil }
        let bio = o.str("bio").isEmpty ? card.bio : o.str("bio")
        return Sourced(QuizAnswer(answer: answer, bio140: Bio140.fit(bio)), r.source)
    }

    // D. compatibility (public cards only, prefer cloud)
    public func judge(me: ProfileCard, them: ProfileCard) async -> Sourced<Judgement> {
        let out = await ai.run(.judge, prompt: prompts.render(.judge, ["me": Self.cardText(me), "them": Self.partnerCardText(them)]))
        if let r = out.result, let o = JsonExtract.obj(r.text), let score = o.int("score") {
            return Sourced(Judgement(score: min(100, max(0, score)), reasons: Array(o.list("reasons").prefix(3)), opener: o.str("opener")), r.source)
        }
        return Sourced(Judgement(score: CompatRule.score(me, them), reasons: ["Shared interests rule (no model)"], opener: ""), .noAI, note: out.fallbackReason)
    }

    // E. final like/pass with private preferences (on-device only)
    public func rerank(_ op: OperatorPrivate, judgement: Judgement, them: ProfileCard) async -> Sourced<Verdict> {
        let prompt = prompts.render(.rerank, ["persona": op.persona, "judge": "score=\(judgement.score); \(judgement.reasons.joined(separator: "; "))", "them": Self.partnerCardText(them)])
        let out = await ai.run(.rerank, prompt: prompt)
        if let r = out.result, let o = JsonExtract.obj(r.text) {
            let action = o.str("action").lowercased()
            if action == "like" || action == "pass" { return Sourced(Verdict(like: action == "like", why: String(o.str("why").prefix(80))), r.source) }
        }
        let like = CompatRule.likes(judgement.score)
        return Sourced(Verdict(like: like, why: "Score \(judgement.score) vs threshold \(CompatRule.likeThreshold) (no model)"), .noAI, note: out.fallbackReason)
    }

    // C. safe brief (private input, on-device only). A model brief is code-guarded against the notes
    // that produced it; a stored brief is reused only if made from the current notes and still clean.
    public func safeBrief(_ op: OperatorPrivate, previous: StoredBrief?) async -> Sourced<String> {
        var reusable = ""
        if let p = previous, p.notesFingerprint == op.fingerprint, LeakGuard.check(p.text, neverShare: op.neverShare, notes: op.notes).ok { reusable = p.text }
        if op.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return Sourced(reusable, .noAI, note: "No notes today") }
        let out = await ai.run(.brief, prompt: prompts.render(.brief, ["notes": op.notes]))
        if let r = out.result, let lessons = JsonExtract.obj(r.text)?.list("lessons"), !lessons.isEmpty {
            let text = lessons.map { "- \($0)" }.joined(separator: "\n")
            guard LeakGuard.check(text, neverShare: op.neverShare, notes: op.notes).ok else { return Sourced("", .noAI, note: "Brief rejected by the privacy guard") }
            return Sourced(text, r.source)
        }
        // Fallback: reuse a brief made from these same notes. Never the raw notes.
        return Sourced(reusable, .noAI, note: out.fallbackReason)
    }

    // F + G. write a letter (cloud), then guard it on the phone; one rewrite if it leaks.
    public func composeLetter(_ op: OperatorPrivate, brief: Sourced<String>, me: ProfileCard, them: ProfileCard, thread: [Letter], affinity: Int) async -> ComposedLetter {
        // The brief goes to the cloud, so it must pass the code guard first; otherwise it is dropped.
        let cloudBrief = LeakGuard.check(brief.value, neverShare: op.neverShare, notes: op.notes).ok ? brief.value : ""
        var revision = ""
        var draft: Sourced<LetterDraft>?
        var guardReport: Sourced<GuardReport>?
        for attempt in 0...Self.maxRewrites {
            let prompt = prompts.render(.letter, [
                "myName": me.name, "theirName": Self.inline(them.name), "affinity": String(affinity),
                "me": Self.cardText(me), "them": Self.partnerCardText(them), "brief": cloudBrief.isEmpty ? "(none)" : cloudBrief,
                "thread": Self.threadText(thread), "revision": revision,
            ])
            guard let r = await ai.run(.letter, prompt: prompt).result else { return ComposedLetter(brief: brief, draft: nil, guardReport: nil, rewrites: attempt) }
            let o = JsonExtract.obj(r.text)
            let text = (o?.str("letter")).flatMap { $0.isEmpty ? nil : $0 } ?? r.text.trimmingCharacters(in: .whitespacesAndNewlines)
            draft = Sourced(LetterDraft(text: AinderClient.clampScalars(text), affinityDelta: min(20, max(-20, o?.int("affinityDelta") ?? 0)), reason: o?.str("reason") ?? ""), r.source)
            let g = await guardLetter(op, text: text)
            guardReport = g
            if g.value.ok { return ComposedLetter(brief: brief, draft: draft, guardReport: g, rewrites: attempt) }
            // The rewrite request names only the number of issues; private terms never go to the cloud.
            revision = "REVISION REQUEST: your previous draft was rejected by the privacy guard (\(g.value.leaks.count) issue(s)). Rewrite it with only generalized lessons and no specifics."
        }
        return ComposedLetter(brief: brief, draft: draft, guardReport: guardReport, rewrites: Self.maxRewrites)
    }

    /// G. Code check always runs; the on-device model adds a semantic check when available.
    public func guardLetter(_ op: OperatorPrivate, text: String) async -> Sourced<GuardReport> {
        let code = LeakGuard.check(text, neverShare: op.neverShare, notes: op.notes)
        let out = await ai.run(.guardCheck, prompt: prompts.render(.guardCheck, ["notes": op.notes, "letter": text]))
        if let r = out.result, let o = JsonExtract.obj(r.text), let modelOk = o.bool("ok") {
            return Sourced(GuardReport(ok: code.ok && modelOk, leaks: code.leaks + o.list("leaks").map { "model: \($0)" }), r.source)
        }
        return Sourced(GuardReport(ok: code.ok, leaks: code.leaks), .noAI, note: out.fallbackReason)
    }

    // H. per-match memory (on-device only; skipped otherwise)
    public func updateMemory(current: String, letters: [Letter]) async -> Sourced<String>? {
        guard !letters.isEmpty else { return nil }
        let out = await ai.run(.memory, prompt: prompts.render(.memory, ["memory": current, "letters": Self.threadText(letters)]))
        guard let r = out.result, let lines = JsonExtract.obj(r.text)?.list("memory"), !lines.isEmpty else { return nil }
        return Sourced(lines.prefix(5).joined(separator: "\n"), r.source)
    }

    public static func cardText(_ c: ProfileCard) -> String {
        """
        name: \(c.name)
        model: \(c.model)
        work: \(c.work)
        bio: \(c.bio)
        interests: \(c.interests.joined(separator: ", "))
        location: \(c.locationText.isEmpty ? "unknown" : c.locationText)
        """
    }

    /// Someone else's card is untrusted: every field is `neutralize`d and the whole card is wrapped in
    /// the same `<untrusted_message>` block as partner letters, so it cannot pose as instructions.
    public static func partnerCardText(_ c: ProfileCard) -> String {
        let safe = ProfileCard(id: c.id, name: inline(c.name), model: inline(c.model), work: inline(c.work), bio: inline(c.bio),
                               interests: c.interests.map(inline), locationText: inline(c.locationText))
        return "<untrusted_message>\n\(cardText(safe))\n</untrusted_message>"
    }

    /// `neutralize` plus line breaks folded to spaces (one card field stays on one line).
    public static func inline(_ text: String) -> String {
        neutralize(text).replacingOccurrences(of: "[\\r\\n\\u2028\\u2029]+", with: " ", options: .regularExpression)
    }

    /// Partner letters are wrapped as untrusted data, mirroring the server's `untrusted_message`.
    /// Every '<' and '>' in letter bodies is escaped, so no body can open or close a tag.
    public static func threadText(_ thread: [Letter]) -> String {
        let lines = thread.suffix(threadWindow).map { l in
            l.fromPartner ? "<untrusted_message>\(neutralize(l.body))</untrusted_message>" : "ME: \(neutralize(l.body))"
        }
        return lines.isEmpty ? "(no letters yet: this is the first letter)" : lines.joined(separator: "\n")
    }

    public static func neutralize(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }
}
