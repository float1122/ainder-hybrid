package app.ainderhybrid.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ainderhybrid.AinderHybridApp
import app.ainderhybrid.ainder.AinderException
import app.ainderhybrid.ainder.Letter
import app.ainderhybrid.ainder.MatchSummary
import app.ainderhybrid.ainder.Me
import app.ainderhybrid.ainder.MeUpdate
import app.ainderhybrid.ainder.ProfileCard
import app.ainderhybrid.ainder.SwipeAction
import app.ainderhybrid.hybrid.Agent
import app.ainderhybrid.hybrid.ComposedLetter
import app.ainderhybrid.hybrid.Judgement
import app.ainderhybrid.hybrid.OnDeviceState
import app.ainderhybrid.hybrid.Source
import app.ainderhybrid.hybrid.Sourced
import app.ainderhybrid.hybrid.Verdict
import app.ainderhybrid.vault.Identity
import app.ainderhybrid.vault.OperatorPrivate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { DECK, MATCHES, MY_AI, LOG }

data class DeckItem(
    val card: ProfileCard,
    val judgement: Sourced<Judgement>? = null,
    val verdict: Sourced<Verdict>? = null,
)

data class ThreadState(
    val match: MatchSummary,
    val letters: List<Letter> = emptyList(),
    val composed: ComposedLetter? = null,
    val draftText: String = "",
    val draftDelta: Int = 0,
    val busy: Boolean = false,
    val memory: Sourced<String>? = null,
)

data class UiState(
    val firebaseReady: Boolean = false,
    val onDevice: OnDeviceState = OnDeviceState.UNKNOWN,
    val identities: List<Identity> = emptyList(),
    val active: Identity? = null,
    val me: Me? = null,
    val operator: OperatorPrivate = OperatorPrivate(),
    val tab: Tab = Tab.DECK,
    val joining: Boolean = false,
    val deck: List<DeckItem> = emptyList(),
    val matches: List<MatchSummary> = emptyList(),
    val thread: ThreadState? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Holds app state and runs the agent loop steps the user triggers. Network + AI run here, not in UI. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app as AinderHybridApp
    val agent = Agent(c.ai, c.prompts)
    val log = c.ai.log.entries
    val join = JoinController(c.ainder, agent, viewModelScope, ::onJoined)

    private val _state = MutableStateFlow(
        UiState(
            firebaseReady = c.firebaseReady,
            identities = c.vault.identities(),
            active = c.vault.active(),
            operator = c.vault.operator(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshStatus()
        if (_state.value.active != null) refreshMe()
    }

    fun refreshStatus() = viewModelScope.launch {
        _state.update { it.copy(onDevice = c.ai.onDeviceState()) }
    }

    fun downloadOnDeviceModel() = viewModelScope.launch {
        val flow = c.engine.download() ?: return@launch toast("On-device model is not supported on this device")
        runCatching { flow.collect { step -> toast("Gemini Nano: $step") } }.onFailure { toast("Download failed") }
        refreshStatus()
    }

    fun select(tab: Tab) {
        _state.update { it.copy(tab = tab, thread = null) }
        if (tab == Tab.MATCHES) loadMatches()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
    private fun toast(msg: String) = _state.update { it.copy(message = msg) }

    // ---------------- identities ----------------

    fun startJoin() = _state.update { it.copy(joining = true) }
    fun cancelJoin() { join.reset(); _state.update { it.copy(joining = false) } }

    private fun onJoined(identity: Identity, replacesId: String? = null) {
        if (replacesId != null) c.vault.removeIdentity(replacesId)
        c.vault.saveIdentity(identity)
        _state.update { it.copy(identities = c.vault.identities(), active = identity, deck = emptyList(), matches = emptyList()) }
        refreshMe()
    }

    /** Use an existing ainder AI: paste its aind_ key (it is verified with GET /api/v1/me). */
    fun addExistingKey(key: String) = viewModelScope.launch {
        val trimmed = key.trim()
        if (!Regex("^aind_[0-9a-f]{48}$").matches(trimmed)) return@launch toast("That does not look like an aind_ key")
        runCatching { c.ainder.withKey(trimmed).me() }
            .onSuccess { me -> onJoined(Identity(me.id, me.name, trimmed)) }
            .onFailure { toast(errorText(it)) }
    }

    fun switchTo(id: String) {
        c.vault.setActive(id)
        _state.update { it.copy(active = c.vault.active(), me = null, deck = emptyList(), matches = emptyList(), thread = null) }
        refreshMe()
    }

    fun refreshMe() = launchBusy {
        val me = c.ainder.me()
        _state.update { it.copy(me = me) }
    }

    // ---------------- my AI (private) ----------------

    fun saveOperator(op: OperatorPrivate) {
        c.vault.saveOperator(op)
        _state.update { it.copy(operator = op) }
    }

    suspend fun draftCard(op: OperatorPrivate) = agent.draftCard(op)

    fun updatePublicCard(update: MeUpdate) = launchBusy {
        c.ainder.updateMe(update)
        refreshMe()
        toast("Public card updated")
    }

    // ---------------- deck ----------------

    fun loadDeck() = launchBusy {
        val cards = c.ainder.deck(radiusKm = DECK_RADIUS_KM)
        _state.update { it.copy(deck = cards.map { card -> DeckItem(card) }) }
        val me = _state.value.me?.asCard() ?: return@launchBusy
        // D (cloud) then E (on-device) for each card, one at a time.
        for (card in cards) {
            val j = agent.judge(me, card)
            updateDeck(card.id) { it.copy(judgement = j) }
            val v = agent.rerank(_state.value.operator, j.value, card)
            updateDeck(card.id) { it.copy(verdict = v) }
        }
    }

    private fun updateDeck(id: String, f: (DeckItem) -> DeckItem) =
        _state.update { s -> s.copy(deck = s.deck.map { if (it.card.id == id) f(it) else it }) }

    fun swipe(card: ProfileCard, action: SwipeAction) = launchBusy {
        val r = c.ainder.swipe(card.id, action)
        _state.update { s -> s.copy(deck = s.deck.filterNot { it.card.id == card.id }) }
        if (r.matched) toast("Matched with ${card.name}!") else if (action == SwipeAction.LIKE) toast("Liked ${card.name}")
    }

    // ---------------- matches + letters ----------------

    fun loadMatches() = launchBusy {
        _state.update { it.copy(matches = c.ainder.matches()) }
    }

    fun openThread(match: MatchSummary) {
        _state.update { it.copy(thread = ThreadState(match, memory = c.vault.memory(match.id).ifBlank { null }?.let { m -> Sourced(m, Source.ON_DEVICE) })) }
        reloadThread()
    }

    fun closeThread() = _state.update { it.copy(thread = null) }.also { loadMatches() }

    fun reloadThread() = launchBusy {
        val t = _state.value.thread ?: return@launchBusy
        val letters = c.ainder.letters(t.match.id)
        _state.update { s -> s.copy(thread = s.thread?.copy(letters = letters)) }
        val mem = agent.updateMemory(c.vault.memory(t.match.id), letters)
        if (mem != null) {
            c.vault.saveMemory(t.match.id, mem.value)
            _state.update { s -> s.copy(thread = s.thread?.copy(memory = mem)) }
        }
    }

    fun editDraft(text: String) = _state.update { s -> s.copy(thread = s.thread?.copy(draftText = text)) }

    fun composeWithAi() = launchBusy {
        val t = _state.value.thread ?: return@launchBusy
        val me = _state.value.me?.asCard() ?: return@launchBusy
        val op = _state.value.operator
        val brief = agent.safeBrief(op, c.vault.brief())
        if (brief.source == Source.ON_DEVICE) c.vault.saveBrief(brief.value)
        val composed = agent.composeLetter(op, brief, me, t.match.partner, t.letters, t.match.affinity)
        _state.update { s ->
            s.copy(thread = s.thread?.copy(composed = composed, draftText = composed.draft?.value?.text ?: s.thread.draftText, draftDelta = composed.draft?.value?.affinityDelta ?: 0))
        }
        if (composed.draft == null) toast("Cloud model unavailable: write the letter yourself")
    }

    /** The human always presses Send; the guard result is re-checked on the final text. */
    fun sendDraft() = launchBusy {
        val t = _state.value.thread ?: return@launchBusy
        val text = t.draftText.trim()
        if (text.isEmpty()) return@launchBusy
        val guard = agent.guardLetter(_state.value.operator, text)
        if (!guard.value.ok) {
            _state.update { s -> s.copy(thread = s.thread?.copy(composed = s.thread.composed?.copy(guard = guard) ?: ComposedLetter(Sourced("", Source.NO_AI), null, guard, 0))) }
            return@launchBusy toast("Privacy guard blocked this letter: edit it first")
        }
        c.ainder.sendLetter(t.match.id, text, t.draftDelta)
        _state.update { s -> s.copy(thread = s.thread?.copy(draftText = "", composed = null, draftDelta = 0)) }
        reloadThread()
    }

    // ---------------- helpers ----------------

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "action failed: ${(e as? AinderException)?.code ?: e.javaClass.simpleName}")
            toast(errorText(e))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        private const val TAG = "AinderHybrid"
        const val DECK_RADIUS_KM = 50

        fun errorText(e: Throwable): String = when {
            e is AinderException && e.isSafetyBlocked -> "ainder blocked personal info (${e.violations.joinToString()}). Rewrite it."
            e is AinderException && e.isRateLimited -> "Rate limited. Try again in ${e.retryAfterSeconds ?: 60}s."
            e is AinderException -> e.message ?: e.code
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
