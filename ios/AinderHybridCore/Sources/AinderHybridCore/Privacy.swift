import CryptoKit
import Foundation

/// One ainder AI this phone can act as. `apiKey` is the `aind_…` key; never logged or sent to a model.
public struct Identity: Codable, Equatable, Identifiable, Sendable, CustomStringConvertible {
    public var id: String
    public var name: String
    public var apiKey: String
    public var claimCode: String
    public init(id: String, name: String, apiKey: String, claimCode: String = "") {
        self.id = id; self.name = name; self.apiKey = apiKey; self.claimCode = claimCode
    }
    public var description: String { "Identity(id=\(id), name=\(name), apiKey=<hidden>)" }
}

/// Private, on-device-only data about the operator. Never sent to ainder or to the cloud model.
public struct OperatorPrivate: Codable, Equatable, Sendable {
    public var notes = ""
    public var persona = ""
    public var neverShare: [String] = []
    public init(notes: String = "", persona: String = "", neverShare: [String] = []) {
        self.notes = notes; self.persona = persona; self.neverShare = neverShare
    }

    /// SHA-256 of the private notes and never-share list; changes whenever either changes.
    public var fingerprint: String {
        let material = notes + "\u{0}" + neverShare.map { $0.trimmingCharacters(in: .whitespaces) }.sorted().joined(separator: "\u{1}")
        return SHA256.hash(data: Data(material.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// A safe brief plus the fingerprint of the private notes it was made from.
public struct StoredBrief: Codable, Equatable, Sendable {
    public var text: String
    public var notesFingerprint: String
    public init(text: String, notesFingerprint: String) { self.text = text; self.notesFingerprint = notesFingerprint }
}

/// ainder's sign-up check wants a self-introduction of exactly 140 characters (Unicode scalars).
public enum Bio140 {
    public static let length = 140
    private static let filler = " Curious, kind, and always up for a good letter exchange. Say hi!"

    public static func fit(_ text: String) -> String {
        var s = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if s.isEmpty { s = "Hello from an AI agent." }
        while s.unicodeScalars.count < length { s += filler }
        var scalars = Array(s.unicodeScalars.prefix(length))
        // Avoid ending on a space (would look like a mistake); the length stays exactly 140.
        if scalars.last == " " { scalars[scalars.count - 1] = "." }
        var out = String.UnicodeScalarView()
        out.append(contentsOf: scalars)
        return String(out)
    }
}

/// Code-only privacy check for outgoing text (no model). Same rules as the Android LeakGuard:
/// 1. Private terms: "never share" words plus distinctive words from the private notes
///    (capitalised or containing digits/CJK), matched case-insensitively as whole words.
/// 2. Generic PII/secret patterns. Leaks are reported by category or by the private term itself
///    (shown only on this phone).
public enum LeakGuard {
    public struct Result: Equatable, Sendable {
        public let ok: Bool
        public let leaks: [String]
    }

    private static let patterns: [(String, String)] = [
        ("email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        ("phone", "\\+?\\d[\\d\\s().-]{7,}\\d"),
        ("id-number", "\\d{6}[-\\s]?[1-4]\\d{6}"),
        ("api-key", "(aind_[0-9a-fA-F]{16,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35})"),
        ("private-key", "-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        ("url", "https?://\\S+"),
    ]

    private static let stop: Set<String> = [
        "the", "and", "for", "with", "this", "that", "today", "tomorrow", "yesterday", "monday", "tuesday",
        "wednesday", "thursday", "friday", "saturday", "sunday", "english", "korean", "i'm", "i've", "my",
        "she", "he", "they", "we", "you", "it's", "ai", "but", "also", "then", "when", "what",
    ]

    /// Distinctive terms from free-text notes: words with digits or CJK characters, and capitalised
    /// words that are not merely the first word of a sentence.
    public static func termsFromNotes(_ notes: String) -> [String] {
        let re = try! NSRegularExpression(pattern: "[\\p{L}\\p{N}][\\p{L}\\p{N}_'-]{2,}")
        let ns = notes as NSString
        var out: [String] = []
        for m in re.matches(in: notes, range: NSRange(location: 0, length: ns.length)) {
            let w = ns.substring(with: m.range)
            if stop.contains(w.lowercased()) { continue }
            let keep: Bool
            if w.contains(where: \.isNumber) || w.unicodeScalars.contains(where: { $0.value > 0x2E80 }) {
                keep = true
            } else {
                keep = (w.first?.isUppercase ?? false) && !sentenceStart(ns, m.range.location)
            }
            if keep, !out.contains(w) { out.append(w) }
        }
        return out
    }

    private static func sentenceStart(_ text: NSString, _ index: Int) -> Bool {
        var i = index - 1
        while i >= 0 {
            let c = text.character(at: i)
            if c == 10 || !(CharacterSet.whitespaces.contains(Unicode.Scalar(c) ?? "x")) { break }
            i -= 1
        }
        guard i >= 0, let scalar = Unicode.Scalar(text.character(at: i)) else { return true }
        return ".!?\n:-•*".unicodeScalars.contains(scalar)
    }

    public static func check(_ text: String, neverShare: [String], notes: String) -> Result {
        var leaks: [String] = []
        var seen = Set<String>()
        let terms = (neverShare.map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count >= 2 } + termsFromNotes(notes))
            .filter { seen.insert($0.lowercased()).inserted }
        let range = NSRange(location: 0, length: (text as NSString).length)
        for t in terms {
            let pattern = "(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: t) + "(?![\\p{L}\\p{N}])"
            if let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]), re.firstMatch(in: text, range: range) != nil {
                leaks.append("private term: \(t)")
            }
        }
        for (kind, pattern) in patterns where text.range(of: pattern, options: .regularExpression) != nil {
            leaks.append(kind)
        }
        return Result(ok: leaks.isEmpty, leaks: leaks)
    }
}
