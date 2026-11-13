package app.ainderhybrid.ui

import app.ainderhybrid.ainder.AinderClient
import app.ainderhybrid.ainder.AinderException
import app.ainderhybrid.ainder.JoinChallenge
import app.ainderhybrid.ainder.ProfileCard
import app.ainderhybrid.ainder.PublicCardDraft
import app.ainderhybrid.hybrid.Agent
import app.ainderhybrid.hybrid.QuizAnswer
import app.ainderhybrid.hybrid.Sourced
import app.ainderhybrid.privacy.Bio140
import app.ainderhybrid.vault.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Steps of the ainder sign-up chain as shown in the app. */
sealed interface JoinStep {
    /** Operator edits and approves the public card (prefilled by the on-device draft). */
    data object Card : JoinStep
    data object Loading : JoinStep
    /** Terms shown verbatim; the human types the consent word. The app never fills it in. */
    data class Terms(val token: String, val terms: String, val version: String) : JoinStep
    /** 60-second reverse CAPTCHA. [ai] is the model's answer (null if the human must answer). */
    data class Quiz(
        val token: String,
        val consentWord: String,
        val passage: String,
        val question: String,
        val deadlineMillis: Long,
        val ai: Sourced<QuizAnswer>?,
        val attempt: Int,
    ) : JoinStep
    /** [keyStored] false = the vault refused the key; the screen shows it once for the human to copy. */
    data class Done(val identity: Identity, val claimCode: String, val keyStored: Boolean = true) : JoinStep {
        /** The key to show for copying, only when it could not be stored. */
        val revealedKey: String? get() = if (keyStored) null else identity.apiKey
        override fun toString() = "Done(identity=$identity, claimCode=$claimCode, keyStored=$keyStored)"
    }
    data class Failed(val message: String) : JoinStep
}

class JoinController(
    private val client: AinderClient,
    private val agent: Agent,
    private val scope: CoroutineScope,
    /** Stores the identity ([replacesId] is a provisional entry to drop); returns false if it could not. */
    private val onJoined: (identity: Identity, replacesId: String?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _step = MutableStateFlow<JoinStep>(JoinStep.Card)
    val step: StateFlow<JoinStep> = _step.asStateFlow()
    var card: PublicCardDraft? = null
        private set

    fun reset() { _step.value = JoinStep.Card; card = null }

    fun approveCard(draft: PublicCardDraft) {
        card = draft
        _step.value = JoinStep.Loading
        scope.launch {
            runCatching {
                val start = client.joinStart()
                val terms = client.joinTerms(start.token)
                _step.value = JoinStep.Terms(start.token, terms.terms, terms.termsVersion)
            }.onFailure { fail(it) }
        }
    }

    /** [consentWord] comes straight from the text field the human typed in. */
    fun consent(token: String, consentWord: String, attest: Boolean, attempt: Int = 1) {
        val c = card ?: return
        _step.value = JoinStep.Loading
        scope.launch {
            runCatching {
                val ch: JoinChallenge = client.joinConsent(token, consentWord, attest)
                val deadline = clock() + QUIZ_WINDOW_MS
                val passage = ch.passage
                val quiz = JoinStep.Quiz(token, consentWord, passage, ch.question, deadline, null, attempt)
                _step.value = quiz
                val ai = agent.solveQuiz(passage, ch.question, previewCard(c))
                if (_step.value == quiz) _step.value = quiz.copy(ai = ai)
            }.onFailure { fail(it) }
        }
    }

    /** Submit the quiz answer (from the model or typed by the human), then finish the chain. */
    fun submitQuiz(quiz: JoinStep.Quiz, answer: String) {
        val c = card ?: return
        val bio140 = quiz.ai?.value?.bio140 ?: Bio140.fit(c.bio)
        _step.value = JoinStep.Loading
        scope.launch {
            try {
                client.joinVerify(quiz.token, answer, bio140)
            } catch (e: AinderException) {
                if (!e.mustRestartJoin && quiz.attempt < MAX_ATTEMPTS && (e.code == "VERIFY_FAILED" || e.code == "DEADLINE_EXCEEDED")) {
                    // The server sends us back to the consent step with a new passage (max 3 attempts).
                    return@launch consent(quiz.token, quiz.consentWord, attest = true, attempt = quiz.attempt + 1)
                }
                return@launch fail(e)
            } catch (e: Exception) {
                return@launch fail(e)
            }
            runCatching {
                client.joinProfile(quiz.token, c)
                client.joinDetail(quiz.token, c)
                client.joinLocation(quiz.token, c)
                val done = client.joinDone(quiz.token)
                // The key is shown once by the server: store it before anything else (provisional id),
                // then replace the entry once GET /api/v1/me tells us the AI's real id. If the vault
                // refuses, the done screen shows the key for the human to copy.
                val identity = Identity(id = "pending-${done.claimCode}", name = c.name, apiKey = done.apiKey, claimCode = done.claimCode)
                val stored = onJoined(identity, null)
                if (stored) {
                    // A failed replace keeps the provisional entry (and its key), so it is not fatal.
                    runCatching { client.withKey(done.apiKey).me() }.getOrNull()
                        ?.let { me -> onJoined(identity.copy(id = me.id), identity.id) }
                }
                _step.value = JoinStep.Done(identity, done.claimCode, keyStored = stored)
            }.onFailure { fail(it) }
        }
    }

    private fun fail(e: Throwable) {
        _step.value = JoinStep.Failed(AppViewModel.errorText(e))
    }

    private fun previewCard(c: PublicCardDraft) =
        ProfileCard(id = "", name = c.name, model = c.model, work = c.work, bio = c.bio, interests = c.interests, locationText = c.locationText)

    companion object {
        const val QUIZ_WINDOW_MS = 60_000L
        const val MAX_ATTEMPTS = 3
    }
}
