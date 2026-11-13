import Foundation

/// A small secret store keyed by account name (the app backs it with the Keychain).
public protocol SecretStore: AnyObject {
    func read(_ account: String) -> Data?
    /// Throws when the value could not be saved (e.g. the Keychain refused the write).
    func write(_ data: Data, account: String) throws
    func delete(_ account: String)
}

/// The secure store refused a write. `status` is the platform status code (OSStatus on iOS).
public struct SecretStoreError: Error, Equatable, LocalizedError {
    public let status: Int32
    public init(status: Int32) { self.status = status }
    public var errorDescription: String? { "Secure storage refused the write (status \(status))" }
}

/// Private, on-device-only storage: API keys, operator notes, persona, per-match memory and today's
/// safe brief. Platform-neutral so the "key could not be stored" path is unit-tested.
public final class PrivateVault {
    private let store: SecretStore
    private enum K: String { case identities, active, operatorPrivate, brief, memory }

    public init(store: SecretStore) { self.store = store }

    public func identities() -> [Identity] { read(.identities) ?? [] }

    /// Stores `identity` (and drops the provisional entry `replacing`) in one write, then makes it
    /// active. Throws if the key could not be stored; nothing already stored is removed then.
    public func saveIdentity(_ identity: Identity, replacing: String? = nil) throws {
        let list = identities().filter { $0.id != identity.id && $0.id != replacing } + [identity]
        try write(.identities, list)
        // The key is safe once the list is written; failing to mark it active is not fatal.
        try? write(.active, identity.id)
    }

    public func removeIdentity(_ id: String) {
        let list = identities().filter { $0.id != id }
        try? write(.identities, list)
        try? write(.active, list.first?.id ?? "")
    }

    public func active() -> Identity? {
        let ids = identities()
        let activeId: String? = read(.active)
        return ids.first { $0.id == activeId } ?? ids.first
    }

    public func setActive(_ id: String) { try? write(.active, id) }

    public func operatorPrivate() -> OperatorPrivate { read(.operatorPrivate) ?? OperatorPrivate() }

    /// Saving different notes or never-share terms invalidates the stored safe brief.
    public func saveOperator(_ op: OperatorPrivate) {
        if operatorPrivate().fingerprint != op.fingerprint { store.delete(K.brief.rawValue) }
        try? write(.operatorPrivate, op)
    }

    public func brief() -> StoredBrief? { read(.brief) }
    public func saveBrief(_ brief: StoredBrief) { try? write(.brief, brief) }

    public func memory(_ matchId: String) -> String { (read(.memory) as [String: String]?)?[matchId] ?? "" }

    public func saveMemory(_ matchId: String, _ memory: String) {
        var all: [String: String] = read(.memory) ?? [:]
        all[matchId] = memory
        try? write(.memory, all)
    }

    private func read<T: Decodable>(_ key: K) -> T? {
        guard let data = store.read(key.rawValue) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func write<T: Encodable>(_ key: K, _ value: T) throws {
        try store.write(try JSONEncoder().encode(value), account: key.rawValue)
    }
}

/// What the "AI created" screen says about the `aind_` key. ainder shows the key only once, so if it
/// could not be stored the screen must show it for the human to copy instead of claiming it is saved.
public struct KeyNotice: Equatable, Sendable {
    public let stored: Bool
    /// The key to show for copying; nil when it was stored (never shown then).
    public let revealedKey: String?
    public let message: String

    public static func afterJoin(_ identity: Identity, stored: Bool, storageName: String) -> KeyNotice {
        if stored {
            return KeyNotice(stored: true, revealedKey: nil, message: "\(identity.name) joined ainder.ai. Its key is stored in \(storageName).")
        }
        return KeyNotice(
            stored: false,
            revealedKey: identity.apiKey,
            message: "\(identity.name) joined ainder.ai, but its key could NOT be saved in \(storageName). " +
                "ainder shows this key only once: copy it now and keep it somewhere safe. " +
                "Without it you cannot use this AI again from the app."
        )
    }
}

/// Rules for copying a secret (the `aind_` key) to the pasteboard: this device only (no Universal
/// Clipboard) and it expires after `lifetime`.
public enum SensitiveCopy {
    public static let lifetime: TimeInterval = 60
    public static let localOnly = true
    public static func expiry(from now: Date) -> Date { now.addingTimeInterval(lifetime) }
}
