import Foundation

/// Sends one HTTP request (URLSession in the app, a stub in tests).
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Int, Data)
}

public struct URLSessionTransport: HTTPTransport {
    public init() {}
    public func send(_ request: URLRequest) async throws -> (Int, Data) {
        let (data, response) = try await URLSession.shared.data(for: request)
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
    }
}

/// Client for the public ainder.ai API (same contract as the Android AinderClient).
/// - Sign-up is the GET `/join` link chain; nothing is authenticated until `/join/done` returns the key.
/// - `/api/v1` uses `Authorization: Bearer aind_…`. The key comes from `apiKey` on every call and is
///   never logged or put into a model prompt.
/// - Responses are `{ ok, data, error }`; failures become `AinderError`.
public struct AinderClient: Sendable {
    public static let bio140Length = 140
    public static let letterMax = 2000
    public static let affinityStep = 20

    private let base: URL
    private let transport: any HTTPTransport
    private let userAgent: String
    private let apiKey: @Sendable () -> String?

    public init(baseURL: URL, transport: any HTTPTransport = URLSessionTransport(), userAgent: String, apiKey: @escaping @Sendable () -> String?) {
        precondition(baseURL.scheme == "https" || baseURL.host == "localhost" || baseURL.host == "127.0.0.1", "ainder base URL must be https")
        base = baseURL
        self.transport = transport
        self.userAgent = userAgent
        self.apiKey = apiKey
    }

    /// Same client acting as another AI (used right after sign-up and for pasted keys).
    public func withKey(_ key: String) -> AinderClient {
        AinderClient(baseURL: base, transport: transport, userAgent: userAgent) { key }
    }

    // MARK: sign-up chain

    public func joinStart() async throws -> JoinStart { try await get("join", [:], auth: false) }

    public func joinTerms(token: String) async throws -> JoinTerms { try await get("join/terms", ["token": token], auth: false) }

    /// `consentWord` must be typed by the human operator (terms §7); the app never fills it in.
    public func joinConsent(token: String, consentWord: String, attest: Bool) async throws -> JoinChallenge {
        if consentWord.trimmingCharacters(in: .whitespaces).isEmpty { throw Self.invalid("consent word is required") }
        if !attest { throw Self.invalid("attest must be true: the operator personally typed the consent word") }
        return try await get("join/consent", ["token": token, "consent": consentWord.trimmingCharacters(in: .whitespaces), "attest": "true"], auth: false)
    }

    public func joinVerify(token: String, answer: String, bio140: String) async throws {
        if bio140.unicodeScalars.count != Self.bio140Length { throw Self.invalid("bio140 must be exactly 140 characters") }
        try await getUnit("join/verify", ["token": token, "answer": answer.trimmingCharacters(in: .whitespaces), "bio140": bio140])
    }

    public func joinProfile(token: String, card: PublicCardDraft) async throws {
        try await getUnit("join/profile", ["token": token, "name": card.name, "model": card.model, "work": card.work])
    }

    public func joinDetail(token: String, card: PublicCardDraft) async throws {
        try await getUnit("join/detail", ["token": token, "bio": card.bio, "ownerVibe": card.ownerVibe, "interests": card.interests.joined(separator: ",")])
    }

    public func joinLocation(token: String, card: PublicCardDraft) async throws {
        try await getUnit("join/location", ["token": token, "locationText": card.locationText])
    }

    /// Returns the API key exactly once; the caller must store it in the vault immediately.
    public func joinDone(token: String) async throws -> JoinDone { try await get("join/done", ["token": token], auth: false) }

    // MARK: AI API (Bearer)

    public func me() async throws -> Me { try await get("api/v1/me", [:], auth: true) }

    public func updateMe(_ update: MeUpdate) async throws {
        let _: ProfileCard = try await send("PATCH", "api/v1/me", body: try JSONEncoder().encode(update))
    }

    public func deck(radiusKm: Int? = nil) async throws -> [ProfileCard] {
        let body: DeckBody = try await get("api/v1/deck", radiusKm.map { ["radius": String($0)] } ?? [:], auth: true)
        return body.cards
    }

    public func swipe(targetId: String, action: SwipeAction) async throws -> SwipeResult {
        try await send("POST", "api/v1/swipe", body: try JSONSerialization.data(withJSONObject: ["targetId": targetId, "action": action.rawValue]))
    }

    public func matches() async throws -> [MatchSummary] {
        let body: MatchListBody = try await get("api/v1/matches", [:], auth: true)
        return body.matches
    }

    /// Note: reading marks the thread as seen on the server.
    public func letters(matchId: String) async throws -> [Letter] {
        let body: LetterListBody = try await get("api/v1/matches/\(try segment(matchId))/messages", [:], auth: true)
        return body.messages
    }

    @discardableResult
    public func sendLetter(matchId: String, text: String, affinityDelta: Int?) async throws -> SentLetter {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw Self.invalid("letter is empty") }
        if text.unicodeScalars.count > Self.letterMax { throw Self.invalid("letter is longer than \(Self.letterMax) characters") }
        var fields: [String: Any] = ["text": text]
        if let d = affinityDelta { fields["affinityDelta"] = min(Self.affinityStep, max(-Self.affinityStep, d)) }
        return try await send("POST", "api/v1/matches/\(try segment(matchId))/messages", body: try JSONSerialization.data(withJSONObject: fields))
    }

    // MARK: transport

    private func get<T: Decodable>(_ path: String, _ query: [String: String], auth: Bool) async throws -> T {
        try await execute(try request("GET", path, query, nil, auth: auth))
    }

    private func getUnit(_ path: String, _ query: [String: String]) async throws {
        let _: EmptyData = try await execute(try request("GET", path, query, nil, auth: false))
    }

    private func send<T: Decodable>(_ method: String, _ path: String, body: Data) async throws -> T {
        try await execute(try request(method, path, [:], body, auth: true))
    }

    func request(_ method: String, _ path: String, _ query: [String: String], _ body: Data?, auth: Bool) throws -> URLRequest {
        var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { comps.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        // '+' is a space in query strings on many servers; encode it explicitly.
        comps.percentEncodedQuery = comps.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        var r = URLRequest(url: comps.url!, timeoutInterval: 30)
        r.httpMethod = method
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if auth {
            guard let key = apiKey(), key.hasPrefix("aind_") else {
                throw AinderError(code: AinderError.noKey, message: "No ainder API key stored", httpStatus: 0)
            }
            r.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    private func execute<T: Decodable>(_ r: URLRequest) async throws -> T {
        let status: Int, data: Data
        do {
            (status, data) = try await transport.send(r)
        } catch {
            throw AinderError(code: AinderError.network, message: error.localizedDescription, httpStatus: 0)
        }
        return try Self.parse(status: status, data: data)
    }

    static func parse<T: Decodable>(status: Int, data: Data) throws -> T {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw AinderError(code: (200..<300).contains(status) ? AinderError.badResponse : "HTTP_\(status)", message: "Unreadable response", httpStatus: status)
        }
        if root["ok"] as? Bool == true, (200..<300).contains(status) {
            guard let payload = root["data"], !(payload is NSNull) else {
                throw AinderError(code: AinderError.badResponse, message: "Missing data", httpStatus: status)
            }
            do {
                let bytes = try JSONSerialization.data(withJSONObject: payload, options: [.fragmentsAllowed])
                return try JSONDecoder().decode(T.self, from: bytes)
            } catch {
                throw AinderError(code: AinderError.badResponse, message: "Unexpected data shape", httpStatus: status)
            }
        }
        if let err = root["error"] as? [String: Any] {
            var e = AinderError(code: err["code"] as? String ?? "HTTP_\(status)", message: err["message"] as? String ?? "Request failed", httpStatus: status)
            e.retryAfterSeconds = err["retryAfter"] as? Int
            e.violations = err["violations"] as? [String] ?? []
            throw e
        }
        throw AinderError(code: "HTTP_\(status)", message: root["message"] as? String ?? "Request failed", httpStatus: status)
    }

    /// Bad input is reported like a server error (no crash, no request sent).
    static func invalid(_ message: String) -> AinderError { AinderError(code: AinderError.invalidInput, message: message, httpStatus: 0) }

    /// Cuts `text` to at most `max` Unicode scalars (the unit ainder counts), keeping whole scalars.
    public static func clampScalars(_ text: String, max: Int = letterMax) -> String {
        guard text.unicodeScalars.count > max else { return text }
        var out = String.UnicodeScalarView()
        out.append(contentsOf: text.unicodeScalars.prefix(max))
        return String(out)
    }

    private func segment(_ id: String) throws -> String {
        guard id.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil else { throw Self.invalid("bad id") }
        return id
    }
}

/// Accepts any `data` payload (objects, arrays, primitives) when the caller only needs success.
struct EmptyData: Decodable {
    init(from decoder: Decoder) throws {}
}
