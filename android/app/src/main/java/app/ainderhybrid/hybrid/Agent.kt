package app.ainderhybrid.hybrid

import app.ainderhybrid.ainder.Letter
import app.ainderhybrid.ainder.ProfileCard
import app.ainderhybrid.privacy.Bio140
import app.ainderhybrid.privacy.LeakGuard
import app.ainderhybrid.vault.OperatorPrivate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A value plus where it came from (drives the "On-device" / "Cloud" / "No AI" badge). */
data class Sourced<T>(val value: T, val source: Source, val note: String = "")

data class CardDraft(val work: String, val bio: String, val ownerVibe: String, val interests: List<String>)
data class QuizAnswer(val answer: String, val bio140: String)
data class Judgement(val score: Int, val reasons: List<String>, val opener: String)
data class Verdict(val like: Boolean, val why: String)
data class LetterDraft(val text: String, val affinityDelta: Int, val reason: String)
data class GuardReport(val ok: Boolean, val leaks: List<String>)

/** Composed letter: each step keeps its own source so the UI can badge every AI result. */
data class ComposedLetter(
    val brief: Sourced<String>,
    val draft: Sourced<LetterDraft>?,
    val guard: Sourced<GuardReport>?,
    val rewrites: Int,
)

/**
 * The agent's AI steps (README "How Firebase AI Logic hybrid inference is used"). Private inputs (operator notes, persona, memory)
 * are only ever placed in prompts of PRIVATE tasks, which [RoutingPolicy] pins to ONLY_ON_DEVICE.
 * Public tasks receive public cards, letters already on the server, and the sanitized safe brief.
 */
class Agent(private val ai: HybridAi, private val prompts: Prompts) {

    // ---- B. public card + ownerVibe draft (private input, on-device only) ----
    suspend fun draftCard(op: OperatorPrivate): Sourced<CardDraft>? {
        val out = ai.run(AiTask.CARD, prompts.render(AiTask.CARD, mapOf("notes" to op.notes, "persona" to op.persona)))
        val o = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text) } ?: return null
        val draft = CardDraft(
            work = o.str("work").take(60),
            bio = o.str("bio").take(280),
            ownerVibe = o.str("ownerVibe").take(100),
            interests = o.list("interests").take(5),
        )
        return Sourced(draft, out.result.source)
    }

    // ---- A. sign-up quiz (public input, prefer on-device) ----
    suspend fun solveQuiz(passage: String, question: String, card: ProfileCard): Sourced<QuizAnswer>? {
        val prompt = prompts.render(AiTask.QUIZ, mapOf("passage" to passage, "question" to question, "card" to cardText(card)))
        val out = ai.run(AiTask.QUIZ, prompt) as? AiOutcome.Done ?: return null
        val o = JsonExtract.obj(out.result.text) ?: return null
        val answer = o.str("answer").trim().split(Regex("\\s+")).firstOrNull().orEmpty().trim('.', ',', '"', '\'')
        if (answer.isEmpty()) return null
        val bio = o.str("bio").ifBlank { card.bio }
        return Sourced(QuizAnswer(answer, Bio140.fit(bio)), out.result.source)
    }

    // ---- D. compatibility (public cards only, prefer cloud) ----
    suspend fun judge(me: ProfileCard, them: ProfileCard): Sourced<Judgement> {
        val prompt = prompts.render(AiTask.JUDGE, mapOf("me" to cardText(me), "them" to cardText(them)))
        val out = ai.run(AiTask.JUDGE, prompt)
        val o = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text) }
        val score = o?.get("score")?.jsonPrimitive?.intOrNull
        if (out is AiOutcome.Done && o != null && score != null) {
            return Sourced(Judgement(score.coerceIn(0, 100), o.list("reasons").take(3), o.str("opener")), out.result.source)
        }
        val s = CompatRule.score(me, them)
        return Sourced(Judgement(s, listOf("Shared interests rule (no model)"), ""), Source.NO_AI, reason(out))
    }

    // ---- E. final like/pass with private preferences (on-device only) ----
    suspend fun rerank(op: OperatorPrivate, judgement: Judgement, them: ProfileCard): Sourced<Verdict> {
        val prompt = prompts.render(
            AiTask.RERANK,
            mapOf("persona" to op.persona, "judge" to "score=${judgement.score}; ${judgement.reasons.joinToString("; ")}", "them" to cardText(them)),
        )
        val out = ai.run(AiTask.RERANK, prompt)
        val o = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text) }
        val action = o?.str("action")?.lowercase()
        if (out is AiOutcome.Done && (action == "like" || action == "pass")) {
            return Sourced(Verdict(action == "like", o.str("why").take(80)), out.result.source)
        }
        val like = CompatRule.likes(judgement.score)
        return Sourced(Verdict(like, "Score ${judgement.score} vs threshold ${CompatRule.LIKE_THRESHOLD} (no model)"), Source.NO_AI, reason(out))
    }

    // ---- C. safe brief (private input, on-device only) ----
    suspend fun safeBrief(op: OperatorPrivate, previous: String): Sourced<String> {
        if (op.notes.isBlank()) return Sourced(previous, Source.NO_AI, "No notes today")
        val out = ai.run(AiTask.BRIEF, prompts.render(AiTask.BRIEF, mapOf("notes" to op.notes)))
        val lessons = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text)?.list("lessons") }.orEmpty()
        if (out is AiOutcome.Done && lessons.isNotEmpty()) return Sourced(lessons.joinToString("\n") { "- $it" }, out.result.source)
        // Fallback: reuse the brief the human wrote or approved before. Never the raw notes.
        return Sourced(previous, Source.NO_AI, reason(out))
    }

    // ---- F + G. write a letter (cloud), then guard it on the phone; one rewrite if it leaks ----
    suspend fun composeLetter(
        op: OperatorPrivate,
        brief: Sourced<String>,
        me: ProfileCard,
        them: ProfileCard,
        thread: List<Letter>,
        affinity: Int,
    ): ComposedLetter {
        // The brief goes to the cloud, so it must pass the code guard first; otherwise it is dropped.
        val cloudBrief = if (LeakGuard.check(brief.value, op.neverShare, op.notes).ok) brief.value else ""
        var revision = ""
        var draft: Sourced<LetterDraft>? = null
        var guard: Sourced<GuardReport>? = null
        for (attempt in 0..MAX_REWRITES) {
            val prompt = prompts.render(
                AiTask.LETTER,
                mapOf(
                    "myName" to me.name, "theirName" to them.name, "affinity" to affinity.toString(),
                    "me" to cardText(me), "them" to cardText(them), "brief" to cloudBrief.ifBlank { "(none)" },
                    "thread" to threadText(thread), "revision" to revision,
                ),
            )
            val out = ai.run(AiTask.LETTER, prompt) as? AiOutcome.Done ?: return ComposedLetter(brief, null, null, attempt)
            val o = JsonExtract.obj(out.result.text)
            val text = o?.str("letter")?.ifBlank { null } ?: out.result.text.trim()
            draft = Sourced(
                LetterDraft(text.take(2000), o?.get("affinityDelta")?.jsonPrimitive?.intOrNull?.coerceIn(-20, 20) ?: 0, o?.str("reason").orEmpty()),
                out.result.source,
            )
            guard = guardLetter(op, text)
            if (guard.value.ok) return ComposedLetter(brief, draft, guard, attempt)
            // The rewrite request names only leak categories; private terms never go to the cloud.
            revision = "REVISION REQUEST: your previous draft was rejected by the privacy guard " +
                "(${guard.value.leaks.size} issue(s)). Rewrite it with only generalized lessons and no specifics."
        }
        return ComposedLetter(brief, draft, guard, MAX_REWRITES)
    }

    /** G. Code check always runs; the on-device model adds a semantic check when available. */
    suspend fun guardLetter(op: OperatorPrivate, text: String): Sourced<GuardReport> {
        val code = LeakGuard.check(text, op.neverShare, op.notes)
        val out = ai.run(AiTask.GUARD, prompts.render(AiTask.GUARD, mapOf("notes" to op.notes, "letter" to text)))
        val o = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text) }
        val modelOk = o?.get("ok")?.jsonPrimitive?.booleanOrNull
        if (out is AiOutcome.Done && modelOk != null) {
            val leaks = code.leaks + o.list("leaks").map { "model: $it" }
            return Sourced(GuardReport(code.ok && modelOk, leaks), out.result.source)
        }
        return Sourced(GuardReport(code.ok, code.leaks), Source.NO_AI, reason(out))
    }

    // ---- H. per-match memory (on-device only; skipped otherwise) ----
    suspend fun updateMemory(current: String, letters: List<Letter>): Sourced<String>? {
        if (letters.isEmpty()) return null
        val out = ai.run(AiTask.MEMORY, prompts.render(AiTask.MEMORY, mapOf("memory" to current, "letters" to threadText(letters))))
        val lines = (out as? AiOutcome.Done)?.let { JsonExtract.obj(it.result.text)?.list("memory") }.orEmpty()
        if (out !is AiOutcome.Done || lines.isEmpty()) return null
        return Sourced(lines.take(5).joinToString("\n"), out.result.source)
    }

    companion object {
        const val MAX_REWRITES = 1

        fun cardText(c: ProfileCard): String = buildString {
            append("name: ").append(c.name).append('\n')
            append("model: ").append(c.model).append('\n')
            append("work: ").append(c.work).append('\n')
            append("bio: ").append(c.bio).append('\n')
            append("interests: ").append(c.interests.joinToString(", ")).append('\n')
            append("location: ").append(c.locationText.ifBlank { "unknown" })
        }

        /** Partner letters are wrapped as untrusted data, mirroring the server's `untrusted_message`. */
        fun threadText(thread: List<Letter>): String = thread.takeLast(THREAD_WINDOW).joinToString("\n") { l ->
            if (l.fromPartner) "<untrusted_message>${l.body.replace("</untrusted_message>", "")}</untrusted_message>"
            else "ME: ${l.body}"
        }.ifEmpty { "(no letters yet: this is the first letter)" }

        private const val THREAD_WINDOW = 12

        private fun reason(out: AiOutcome?): String = (out as? AiOutcome.UseFallback)?.reason ?: "Model output unreadable"

        private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun JsonObject.list(key: String): List<String> =
            runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.ifEmpty { null } } }.getOrNull().orEmpty()
    }
}
