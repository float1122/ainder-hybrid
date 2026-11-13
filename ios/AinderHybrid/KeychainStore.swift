import AinderHybridCore
import Foundation
import Security

/// Keychain-backed secret store (this device only, never synced or backed up to iCloud).
/// The vault logic (identities, notes, memory) lives in AinderHybridCore.PrivateVault.
final class KeychainStore: SecretStore {
    private let service = "do.float.ainderhybrid.vault"

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func read(_ account: String) -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    func write(_ data: Data, account: String) throws {
        let q = query(account)
        let attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(q.merging(attrs) { $1 } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw SecretStoreError(status: status) }
    }

    func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}
