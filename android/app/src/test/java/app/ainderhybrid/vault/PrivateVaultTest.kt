package app.ainderhybrid.vault

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SharedPreferences whose commit can fail or throw (simulates a Keystore failure). */
private class FakePrefs : SharedPreferences {
    val data = mutableMapOf<String, Any?>()
    var commitResult = true
    var throwOnCommit: Exception? = null

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?) = data[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { removed += data.keys }
        override fun commit(): Boolean {
            throwOnCommit?.let { throw it }
            if (!commitResult) return false
            removed.forEach { data.remove(it) }
            data.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}

class PrivateVaultTest {
    private val key = "aind_" + "ab".repeat(24)

    @Test fun `saveIdentity stores the key, replaces the provisional entry and makes it active`() {
        val vault = PrivateVault(FakePrefs())
        assertTrue(vault.saveIdentity(Identity("pending-ABC", "[test] Nova", key, "ABC-1234")))
        assertTrue(vault.saveIdentity(Identity("ai-1", "[test] Nova", key, "ABC-1234"), replacesId = "pending-ABC"))
        assertEquals(listOf("ai-1"), vault.identities().map { it.id })
        assertEquals(key, vault.active()?.apiKey)
    }

    @Test fun `saveIdentity returns false when the store refuses the write`() {
        val prefs = FakePrefs().apply { commitResult = false }
        val vault = PrivateVault(prefs)
        assertFalse(vault.saveIdentity(Identity("x", "n", key)))
        assertTrue(vault.identities().isEmpty())
        assertNull(vault.active())
    }

    @Test fun `saveIdentity returns false instead of crashing when the keystore throws`() {
        val prefs = FakePrefs().apply { throwOnCommit = SecurityException("keystore unavailable") }
        assertFalse(PrivateVault(prefs).saveIdentity(Identity("x", "n", key)))
    }

    @Test fun `a failed replace keeps the provisional key`() {
        val prefs = FakePrefs()
        val vault = PrivateVault(prefs)
        assertTrue(vault.saveIdentity(Identity("pending-ABC", "n", key)))
        prefs.commitResult = false
        assertFalse(vault.saveIdentity(Identity("ai-1", "n", key), replacesId = "pending-ABC"))
        assertEquals(listOf("pending-ABC"), vault.identities().map { it.id })
    }
}
