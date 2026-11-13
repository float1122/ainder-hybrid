import Foundation
import XCTest
@testable import AinderHybridCore

/// In-memory store that can be told to refuse writes (simulates a Keychain failure).
final class MemoryStore: SecretStore {
    var items: [String: Data] = [:]
    var failWrites = false

    func read(_ account: String) -> Data? { items[account] }
    func write(_ data: Data, account: String) throws {
        if failWrites { throw SecretStoreError(status: -34018) } // errSecMissingEntitlement
        items[account] = data
    }
    func delete(_ account: String) { items[account] = nil }
}

final class PrivateVaultTests: XCTestCase {
    private let key = "aind_" + String(repeating: "ab", count: 24)

    func testSaveIdentityStoresKeyAndMakesItActive() throws {
        let vault = PrivateVault(store: MemoryStore())
        try vault.saveIdentity(Identity(id: "pending-ABC", name: "[test] Nova", apiKey: key, claimCode: "ABC-1234"))
        try vault.saveIdentity(Identity(id: "ai-1", name: "[test] Nova", apiKey: key, claimCode: "ABC-1234"), replacing: "pending-ABC")
        XCTAssertEqual(vault.identities().map(\.id), ["ai-1"])
        XCTAssertEqual(vault.active()?.apiKey, key)
    }

    func testSaveIdentityThrowsWhenStoreRefusesWrite() {
        let store = MemoryStore()
        store.failWrites = true
        let vault = PrivateVault(store: store)
        XCTAssertThrowsError(try vault.saveIdentity(Identity(id: "x", name: "n", apiKey: key))) { error in
            XCTAssertEqual(error as? SecretStoreError, SecretStoreError(status: -34018))
        }
        XCTAssertTrue(vault.identities().isEmpty)
        XCTAssertNil(vault.active())
    }

    func testFailedReplaceKeepsTheProvisionalKey() throws {
        let store = MemoryStore()
        let vault = PrivateVault(store: store)
        try vault.saveIdentity(Identity(id: "pending-ABC", name: "n", apiKey: key))
        store.failWrites = true
        XCTAssertThrowsError(try vault.saveIdentity(Identity(id: "ai-1", name: "n", apiKey: key), replacing: "pending-ABC"))
        XCTAssertEqual(vault.identities().map(\.id), ["pending-ABC"], "the stored key must not be dropped")
    }

    func testKeyNoticeHidesKeyWhenStored() {
        let n = KeyNotice.afterJoin(Identity(id: "a", name: "[test] Nova", apiKey: key), stored: true, storageName: "this phone's Keychain")
        XCTAssertTrue(n.stored)
        XCTAssertNil(n.revealedKey)
        XCTAssertTrue(n.message.contains("is stored"))
        XCTAssertFalse(n.message.contains(key))
    }

    func testKeyNoticeRevealsKeyWithWarningWhenNotStored() {
        let n = KeyNotice.afterJoin(Identity(id: "a", name: "[test] Nova", apiKey: key), stored: false, storageName: "this phone's Keychain")
        XCTAssertFalse(n.stored)
        XCTAssertEqual(n.revealedKey, key)
        XCTAssertTrue(n.message.contains("could NOT be saved"))
        XCTAssertTrue(n.message.contains("only once"))
        XCTAssertFalse(n.message.contains("is stored"))
    }
}
