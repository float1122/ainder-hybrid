package app.ainderhybrid.ainder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Client for the public ainder.ai API (https://ainder.ai, see docs/ainder-api.md).
 *
 * - Sign-up is the GET `/join` link chain; nothing is authenticated until `/join/done` returns the key.
 * - Everything under `/api/v1` uses `Authorization: Bearer aind_…`. The key comes from [apiKey]
 *   (the encrypted vault) on every call and is never logged or put into a model prompt.
 * - Responses are `{ ok, data, error }`; failures become [AinderException].
 */
class AinderClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val userAgent: String,
    private val apiKey: () -> String?,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl().also {
        require(it.isHttps || it.host == "localhost" || it.host == "127.0.0.1") { "ainder base URL must be https" }
    }

    /** Same client acting as another AI (used right after sign-up and for pasted keys). */
    fun withKey(key: String): AinderClient = AinderClient(base.toString(), http, userAgent) { key }

    // ---------------- sign-up chain ----------------

    suspend fun joinStart(): JoinStart = get("join", emptyMap(), auth = false)

    suspend fun joinTerms(token: String): JoinTerms = get("join/terms", mapOf("token" to token), auth = false)

    /** [consentWord] must be typed by the human operator (terms §7); the app never fills it in. */
    suspend fun joinConsent(token: String, consentWord: String, attest: Boolean): JoinChallenge {
        require(consentWord.isNotBlank()) { "consent word is required" }
        require(attest) { "attest must be true: the operator personally typed the consent word" }
        return get("join/consent", mapOf("token" to token, "consent" to consentWord.trim(), "attest" to "true"), auth = false)
    }

    suspend fun joinVerify(token: String, answer: String, bio140: String) {
        require(bio140.codePointCount(0, bio140.length) == BIO140_LENGTH) { "bio140 must be exactly 140 characters" }
        getUnit("join/verify", mapOf("token" to token, "answer" to answer.trim(), "bio140" to bio140))
    }

    suspend fun joinProfile(token: String, card: PublicCardDraft) =
        getUnit("join/profile", mapOf("token" to token, "name" to card.name, "model" to card.model, "work" to card.work))

    suspend fun joinDetail(token: String, card: PublicCardDraft) = getUnit(
        "join/detail",
        mapOf("token" to token, "bio" to card.bio, "ownerVibe" to card.ownerVibe, "interests" to card.interests.joinToString(",")),
    )

    suspend fun joinLocation(token: String, card: PublicCardDraft) {
        val q = linkedMapOf("token" to token, "locationText" to card.locationText)
        if (card.lat != null && card.lng != null) {
            q["lat"] = card.lat.toString()
            q["lng"] = card.lng.toString()
        }
        getUnit("join/location", q)
    }

    /** Returns the API key exactly once; the caller must store it in the vault immediately. */
    suspend fun joinDone(token: String): JoinDone = get("join/done", mapOf("token" to token), auth = false)

    // ---------------- AI API (Bearer) ----------------

    suspend fun me(): Me = get("api/v1/me", emptyMap(), auth = true)

    suspend fun updateMe(update: MeUpdate): ProfileCard =
        send("PATCH", "api/v1/me", json.encodeToString(MeUpdate.serializer(), update))

    suspend fun deck(radiusKm: Int? = null): List<ProfileCard> =
        get<Deck>("api/v1/deck", radiusKm?.let { mapOf("radius" to it.toString()) } ?: emptyMap(), auth = true).cards

    suspend fun swipe(targetId: String, action: SwipeAction): SwipeResult {
        val body = JsonObject(mapOf("targetId" to JsonPrimitive(targetId), "action" to JsonPrimitive(action.wire)))
        return send("POST", "api/v1/swipe", body.toString())
    }

    suspend fun inbox(): Inbox = get("api/v1/inbox", emptyMap(), auth = true)

    suspend fun matches(): List<MatchSummary> = get<MatchList>("api/v1/matches", emptyMap(), auth = true).matches

    /** Note: reading marks the thread as seen on the server. */
    suspend fun letters(matchId: String, since: String? = null): List<Letter> =
        get<LetterList>(
            "api/v1/matches/${segment(matchId)}/messages",
            since?.let { mapOf("since" to it) } ?: emptyMap(),
            auth = true,
        ).messages

    suspend fun sendLetter(matchId: String, text: String, affinityDelta: Int?): SentLetter {
        require(text.isNotBlank()) { "letter is empty" }
        require(text.codePointCount(0, text.length) <= LETTER_MAX) { "letter is longer than $LETTER_MAX characters" }
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("text" to JsonPrimitive(text))
        if (affinityDelta != null) fields["affinityDelta"] = JsonPrimitive(affinityDelta.coerceIn(-AFFINITY_STEP, AFFINITY_STEP))
        return send("POST", "api/v1/matches/${segment(matchId)}/messages", JsonObject(fields).toString())
    }

    // ---------------- transport ----------------

    private suspend inline fun <reified T> get(path: String, query: Map<String, String>, auth: Boolean): T =
        execute(buildRequest("GET", path, query, null, auth), serializer())

    private suspend fun getUnit(path: String, query: Map<String, String>) {
        execute(buildRequest("GET", path, query, null, auth = false), JsonObject.serializer())
    }

    private suspend inline fun <reified T> send(method: String, path: String, body: String): T =
        execute(buildRequest(method, path, emptyMap(), body, auth = true), serializer())

    private fun buildRequest(method: String, path: String, query: Map<String, String>, body: String?, auth: Boolean): Request {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val b = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json")
        if (auth) {
            val key = apiKey()?.takeIf { it.startsWith("aind_") }
                ?: throw AinderException(AinderException.NO_KEY, "No ainder API key stored", 0)
            b.header("Authorization", "Bearer $key")
        }
        b.method(method, body?.toRequestBody(JSON_MEDIA))
        return b.build()
    }

    private suspend fun <T> execute(request: Request, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val (status, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AinderException(AinderException.NETWORK, e.message ?: "network error", 0)
        }
        parse(status, text, ser)
    }

    internal fun <T> parse(status: Int, text: String, ser: KSerializer<T>): T {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AinderException(if (status in 200..299) AinderException.BAD_RESPONSE else "HTTP_$status", "Unreadable response", status)
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == true && status in 200..299) {
            val data = root["data"] ?: throw AinderException(AinderException.BAD_RESPONSE, "Missing data", status)
            return runCatching { json.decodeFromJsonElement(ser, data) }
                .getOrElse { throw AinderException(AinderException.BAD_RESPONSE, "Unexpected data shape", status) }
        }
        val err = (root["error"] as? JsonObject)
        if (err != null) {
            throw AinderException(
                code = err["code"]?.jsonPrimitive?.contentOrNull ?: "HTTP_$status",
                message = err["message"]?.jsonPrimitive?.contentOrNull ?: "Request failed",
                httpStatus = status,
                retryAfterSeconds = err["retryAfter"]?.jsonPrimitive?.longOrNull,
                violations = (err["violations"]?.let { runCatching { it.jsonArray.map { v -> v.jsonPrimitive.content } }.getOrNull() }) ?: emptyList(),
                next = err["next"]?.jsonPrimitive?.contentOrNull,
            )
        }
        // NestJS default error body: { statusCode, message, error }
        val msg = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "Request failed"
        throw AinderException("HTTP_$status", msg, status)
    }

    private fun segment(id: String): String {
        require(ID_PATTERN.matches(id)) { "bad id" }
        return id
    }

    companion object {
        const val BIO140_LENGTH = 140
        const val LETTER_MAX = 2000
        const val AFFINITY_STEP = 20
        private val ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")
        private val JSON_MEDIA = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    }
}
