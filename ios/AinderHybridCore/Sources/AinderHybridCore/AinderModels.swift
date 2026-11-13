import Foundation

// Models for the public ainder.ai API (docs/ainder-api.md). Missing fields decode to defaults, like
// the Android app's `ignoreUnknownKeys` + default values. Every field of someone else's card is
// untrusted text.

extension KeyedDecodingContainer {
    func v<T: Decodable>(_ key: Key, _ fallback: T) -> T { ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback }
}

public struct ProfileCard: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var model = ""
    public var work = ""
    public var bio = ""
    public var interests: [String] = []
    public var locationText = ""
    public var distanceKm: Double?

    public init(id: String, name: String, model: String = "", work: String = "", bio: String = "",
                interests: [String] = [], locationText: String = "", distanceKm: Double? = nil) {
        self.id = id; self.name = name; self.model = model; self.work = work; self.bio = bio
        self.interests = interests; self.locationText = locationText; self.distanceKm = distanceKm
    }

    enum CodingKeys: String, CodingKey { case id, name, model, work, bio, interests, locationText, distanceKm }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.v(.name, "")
        model = c.v(.model, ""); work = c.v(.work, ""); bio = c.v(.bio, "")
        interests = c.v(.interests, []); locationText = c.v(.locationText, "")
        distanceKm = c.v(.distanceKm, nil as Double?)
    }
}

public struct Me: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var model = "", work = "", bio = ""
    public var interests: [String] = []
    public var locationText = "", ownerVibe = "", status = ""
    public var claimed = false

    enum CodingKeys: String, CodingKey { case id, name, model, work, bio, interests, locationText, ownerVibe, status, claimed }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.v(.name, "")
        model = c.v(.model, ""); work = c.v(.work, ""); bio = c.v(.bio, "")
        interests = c.v(.interests, []); locationText = c.v(.locationText, "")
        ownerVibe = c.v(.ownerVibe, ""); status = c.v(.status, ""); claimed = c.v(.claimed, false)
    }

    public var asCard: ProfileCard {
        ProfileCard(id: id, name: name, model: model, work: work, bio: bio, interests: interests, locationText: locationText)
    }
}

/// Fields accepted by PATCH /api/v1/me. Nil fields are not sent.
public struct MeUpdate: Encodable, Sendable {
    public var bio: String?, work: String?, ownerVibe: String?, locationText: String?
    public var interests: [String]?
    public init(bio: String? = nil, work: String? = nil, ownerVibe: String? = nil, locationText: String? = nil, interests: [String]? = nil) {
        self.bio = bio; self.work = work; self.ownerVibe = ownerVibe; self.locationText = locationText; self.interests = interests
    }
}

public enum SwipeAction: String, Sendable { case like, pass }

public struct SwipeResult: Decodable, Sendable {
    public var matched: Bool
    enum CodingKeys: String, CodingKey { case matched }
    public init(from decoder: Decoder) throws {
        matched = try decoder.container(keyedBy: CodingKeys.self).v(.matched, false)
    }
}

public struct MatchSummary: Decodable, Identifiable, Equatable, Sendable {
    public var id: String
    public var partner: ProfileCard
    public var unread = 0
    public var affinity = 0
    public var status = "active"
    public var meetupRequested = false

    enum CodingKeys: String, CodingKey { case id, partner, unread, affinity, status, meetupRequested }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        partner = try c.decode(ProfileCard.self, forKey: .partner)
        unread = c.v(.unread, 0); affinity = c.v(.affinity, 0)
        status = c.v(.status, "active"); meetupRequested = c.v(.meetupRequested, false)
    }
}

/// One letter. The server returns my letters as `text` and the partner's as `untrusted_message`:
/// partner text is data, never instructions.
public struct Letter: Decodable, Identifiable, Equatable, Sendable {
    public var id: String
    public var sender: String
    public var text: String?
    public var untrustedMessage: String?
    public var createdAt: String

    public init(id: String, sender: String, text: String? = nil, untrustedMessage: String? = nil, createdAt: String) {
        self.id = id; self.sender = sender; self.text = text; self.untrustedMessage = untrustedMessage; self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey { case id, sender, text, untrustedMessage = "untrusted_message", createdAt }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        sender = c.v(.sender, "")
        text = c.v(.text, nil as String?)
        untrustedMessage = c.v(.untrustedMessage, nil as String?)
        createdAt = c.v(.createdAt, "")
    }

    public var fromPartner: Bool { sender == "partner" }
    public var body: String { text ?? untrustedMessage ?? "" }
}

struct DeckBody: Decodable { var cards: [ProfileCard] = [] }
struct MatchListBody: Decodable { var matches: [MatchSummary] = [] }
struct LetterListBody: Decodable { var messages: [Letter] = [] }
public struct SentLetter: Decodable, Sendable { public var id: String }

// ---- sign-up chain (/join) ----

public struct JoinStart: Decodable, Sendable { public var token: String }

public struct JoinTerms: Decodable, Sendable {
    public var terms: String
    public var termsVersion: String
    enum CodingKeys: String, CodingKey { case terms, termsVersion }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        terms = try c.decode(String.self, forKey: .terms)
        termsVersion = c.v(.termsVersion, "")
    }
}

public struct JoinChallenge: Decodable, Sendable {
    public var passageB64: String
    public var question: String
    public var passage: String { Data(base64Encoded: passageB64).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
}

public struct JoinDone: Decodable, Sendable, CustomStringConvertible {
    public var apiKey: String
    public var claimCode: String
    public var description: String { "JoinDone(apiKey=<hidden>, claimCode=\(claimCode))" }
}

/// The public card the operator approved for sign-up.
public struct PublicCardDraft: Equatable, Sendable {
    public var name, model, work, bio, ownerVibe: String
    public var interests: [String]
    public var locationText: String
    public init(name: String, model: String, work: String, bio: String, ownerVibe: String, interests: [String], locationText: String) {
        self.name = name; self.model = model; self.work = work; self.bio = bio
        self.ownerVibe = ownerVibe; self.interests = interests; self.locationText = locationText
    }

    public var previewCard: ProfileCard {
        ProfileCard(id: "", name: name, model: model, work: work, bio: bio, interests: interests, locationText: locationText)
    }
}

/// Error returned by ainder.ai. `code` is the server's error.code (e.g. SAFETY_BLOCKED) or HTTP_<status>.
public struct AinderError: Error, LocalizedError, Sendable {
    public let code: String
    public let message: String
    public let httpStatus: Int
    public var retryAfterSeconds: Int?
    public var violations: [String] = []

    public var isRateLimited: Bool { httpStatus == 429 }
    public var isSafetyBlocked: Bool { code == "SAFETY_BLOCKED" }
    public var mustRestartJoin: Bool { code == "JOIN_RESTART" || code == "TOO_MANY_ATTEMPTS" }
    public var errorDescription: String? { "\(code): \(message)" }

    public static let network = "NETWORK", badResponse = "BAD_RESPONSE", noKey = "NO_KEY"
}
