package app.ainderhybrid.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.ainderhybrid.hybrid.Agent
import app.ainderhybrid.hybrid.StoredBrief
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One ainder AI this phone can act as. [apiKey] is the `aind_…` key; never logged or sent to a model. */
@Serializable
data class Identity(val id: String, val name: String, val apiKey: String, val claimCode: String = "") {
    override fun toString() = "Identity(id=$id, name=$name, apiKey=<hidden>)"
}

/** Private, on-device-only data about the operator. Never sent to ainder or to the cloud model. */
@Serializable
data class OperatorPrivate(
    val notes: String = "",
    val persona: String = "",
    val neverShare: List<String> = emptyList(),
)

/**
 * Encrypted local storage (Android Keystore-backed AES via androidx.security EncryptedSharedPreferences).
 * Holds API keys, operator notes, persona, per-match memory and today's safe brief.
 */
class PrivateVault internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(encryptedPrefs(context))
    private val json = Json { ignoreUnknownKeys = true }
    private val identitiesSer = ListSerializer(Identity.serializer())
    private val memorySer = MapSerializer(String.serializer(), String.serializer())

    fun identities(): List<Identity> =
        prefs.getString(K_IDS, null)?.let { runCatching { json.decodeFromString(identitiesSer, it) }.getOrNull() } ?: emptyList()

    /**
     * Stores [identity] (dropping the provisional entry [replacesId]) in one synchronous write and makes
     * it active. Returns false if the encrypted store refused the write; nothing stored is removed then.
     */
    fun saveIdentity(identity: Identity, replacesId: String? = null): Boolean {
        val list = identities().filterNot { it.id == identity.id || it.id == replacesId } + identity
        return try {
            prefs.edit().putString(K_IDS, json.encodeToString(identitiesSer, list)).putString(K_ACTIVE, identity.id).commit()
        } catch (e: Exception) {
            // Keystore/crypto failures surface here; the caller shows the key instead of losing it.
            false
        }
    }

    fun removeIdentity(id: String) {
        val list = identities().filterNot { it.id == id }
        prefs.edit().putString(K_IDS, json.encodeToString(identitiesSer, list))
            .putString(K_ACTIVE, list.firstOrNull()?.id).apply()
    }

    fun active(): Identity? {
        val ids = identities()
        val activeId = prefs.getString(K_ACTIVE, null)
        return ids.firstOrNull { it.id == activeId } ?: ids.firstOrNull()
    }

    fun setActive(id: String) = prefs.edit().putString(K_ACTIVE, id).apply()

    fun operator(): OperatorPrivate =
        prefs.getString(K_OPERATOR, null)?.let { runCatching { json.decodeFromString(OperatorPrivate.serializer(), it) }.getOrNull() }
            ?: OperatorPrivate()

    /** Saving different notes or never-share terms invalidates the stored safe brief. */
    fun saveOperator(op: OperatorPrivate) {
        val edit = prefs.edit().putString(K_OPERATOR, json.encodeToString(OperatorPrivate.serializer(), op))
        if (Agent.fingerprint(operator()) != Agent.fingerprint(op)) edit.remove(K_BRIEF)
        edit.apply()
    }

    fun brief(): StoredBrief? =
        prefs.getString(K_BRIEF, null)?.let { runCatching { json.decodeFromString(StoredBrief.serializer(), it) }.getOrNull() }

    fun saveBrief(brief: StoredBrief) = prefs.edit().putString(K_BRIEF, json.encodeToString(StoredBrief.serializer(), brief)).apply()

    fun memory(matchId: String): String = memories()[matchId].orEmpty()

    fun saveMemory(matchId: String, memory: String) {
        val all = memories() + (matchId to memory)
        prefs.edit().putString(K_MEMORY, json.encodeToString(memorySer, all)).apply()
    }

    private fun memories(): Map<String, String> =
        prefs.getString(K_MEMORY, null)?.let { runCatching { json.decodeFromString(memorySer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        // EncryptedSharedPreferences is deprecated but still the simplest Keystore-backed store on API 26+.
        @Suppress("DEPRECATION")
        fun encryptedPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        const val FILE = "ainder_hybrid_vault"
        const val K_IDS = "identities"
        const val K_ACTIVE = "active_identity"
        const val K_OPERATOR = "operator_private"
        const val K_BRIEF = "safe_brief"
        const val K_MEMORY = "match_memory"
    }
}
