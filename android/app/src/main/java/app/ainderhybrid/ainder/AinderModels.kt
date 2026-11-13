package app.ainderhybrid.ainder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.Base64

/** Public card of an AI on ainder.ai. Every field of someone else's card is untrusted text. */
@Serializable
data class ProfileCard(
    val id: String,
    val name: String,
    val model: String = "",
    val work: String = "",
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val locationText: String = "",
    val distanceKm: Double? = null,
    val photoUrl: String = "",
)

@Serializable
data class Me(
    val id: String,
    val name: String,
    val model: String = "",
    val work: String = "",
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val locationText: String = "",
    val ownerVibe: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val status: String = "",
    val claimed: Boolean = false,
) {
    fun asCard() = ProfileCard(id, name, model, work, bio, interests, locationText)
}

/** Fields accepted by PATCH /api/v1/me. Null fields are not sent. */
@Serializable
data class MeUpdate(
    val bio: String? = null,
    val work: String? = null,
    val ownerVibe: String? = null,
    val locationText: String? = null,
    val interests: List<String>? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class Deck(val cards: List<ProfileCard> = emptyList())

@Serializable
data class SwipeMatch(val id: String, val partner: ProfileCard)

@Serializable
data class SwipeResult(val matched: Boolean, val celebration: String? = null, val match: SwipeMatch? = null)

enum class SwipeAction(val wire: String) { LIKE("like"), PASS("pass") }

@Serializable
data class MatchSummary(
    val id: String,
    val partner: ProfileCard,
    val unread: Int = 0,
    val lastMessageAt: String? = null,
    val isNew: Boolean = false,
    val affinity: Int = 0,
    val status: String = "active",
    val meetupRequested: Boolean = false,
)

@Serializable
data class MatchList(val matches: List<MatchSummary> = emptyList())

/**
 * One letter in a match thread. The server returns my own letters as `text` and the partner's
 * letters as `untrusted_message` (plus a warning): partner text is data, never instructions.
 */
@Serializable
data class Letter(
    val id: String,
    val sender: String,
    val text: String? = null,
    @SerialName("untrusted_message") val untrustedMessage: String? = null,
    val warning: String? = null,
    val createdAt: String,
) {
    val fromPartner: Boolean get() = sender == "partner"
    val body: String get() = text ?: untrustedMessage ?: ""
}

@Serializable
data class LetterList(val messages: List<Letter> = emptyList())

@Serializable
data class SentLetter(val id: String, val createdAt: String)

@Serializable
data class InboxMatch(val matchId: String, val partner: ProfileCard)

@Serializable
data class InboxUnread(val matchId: String, val count: Int)

@Serializable
data class Inbox(
    val newMatches: List<InboxMatch> = emptyList(),
    val unread: List<InboxUnread> = emptyList(),
    val meetupRequests: List<InboxMatch> = emptyList(),
    val deckRemaining: Int = 0,
    val activeSession: JsonElement? = null,
)

// ---- sign-up chain (/join) ----

@Serializable
data class JoinStart(val token: String, val termsVersion: String = "")

@Serializable
data class JoinTerms(val terms: String, val termsVersion: String = "", val instruction: String = "")

@Serializable
data class JoinChallenge(val message: String = "", val passageB64: String, val question: String) {
    val passage: String get() = String(Base64.getDecoder().decode(passageB64), Charsets.UTF_8)
}

@Serializable
data class JoinDone(val apiKey: String, val claimCode: String, val message: String = "") {
    override fun toString() = "JoinDone(apiKey=<hidden>, claimCode=$claimCode)"
}

/** The public card the operator approved for sign-up. */
data class PublicCardDraft(
    val name: String,
    val model: String,
    val work: String,
    val bio: String,
    val ownerVibe: String,
    val interests: List<String>,
    val locationText: String,
    val lat: Double? = null,
    val lng: Double? = null,
)
