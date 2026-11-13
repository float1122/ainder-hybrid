import AinderHybridCore
import Foundation
import Security

/// Keychain-backed private storage (this device only, never synced or backed up to iCloud).
/// Holds API keys, operator notes, persona, per-match memory and today's safe brief.
final class KeychainVault {
    private let service = "do.float.ainderhybrid.vault"

    private enum K: String { case identities, active, operatorPrivate, brief, memory }

    func identities() -> [Identity] { read(.identities) ?? [] }

    func saveIdentity(_ identity: Identity) {
        write(.identities, identities().filter { $0.id != identity.id } + [identity])
        write(.active, identity.id)
    }

    func removeIdentity(_ id: String) {
        let list = identities().filter { $0.id != id }
        write(.identities, list)
        write(.active, list.first?.id ?? "")
    }

    func active() -> Identity? {
        let ids = identities()
        let activeId: String? = read(.active)
        return ids.first { $0.id == activeId } ?? ids.first
    }

    func setActive(_ id: String) { write(.active, id) }

    func operatorPrivate() -> OperatorPrivate { read(.operatorPrivate) ?? OperatorPrivate() }

    /// Saving different notes or never-share terms invalidates the stored safe brief.
    func saveOperator(_ op: OperatorPrivate) {
        if operatorPrivate().fingerprint != op.fingerprint { delete(.brief) }
        write(.operatorPrivate, op)
    }

    func brief() -> StoredBrief? { read(.brief) }
    func saveBrief(_ brief: StoredBrief) { write(.brief, brief) }

    func memory(_ matchId: String) -> String { (read(.memory) as [String: String]?)?[matchId] ?? "" }

    func saveMemory(_ matchId: String, _ memory: String) {
        var all: [String: String] = read(.memory) ?? [:]
        all[matchId] = memory
        write(.memory, all)
    }

    // MARK: Keychain

    private func query(_ key: K) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key.rawValue]
    }

    private func read<T: Decodable>(_ key: K) -> T? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func write<T: Encodable>(_ key: K, _ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        let q = query(key)
        let attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        if SecItemUpdate(q as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            SecItemAdd(q.merging(attrs) { $1 } as CFDictionary, nil)
        }
    }

    private func delete(_ key: K) { SecItemDelete(query(key) as CFDictionary) }
}
