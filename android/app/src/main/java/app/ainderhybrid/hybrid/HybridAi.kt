package app.ainderhybrid.hybrid

import kotlinx.coroutines.CancellationException

data class AiResult(val text: String, val source: Source, val ms: Long, val cloudChars: Int)

sealed interface AiOutcome {
    data class Done(val result: AiResult) : AiOutcome
    data class UseFallback(val fallback: Fallback, val reason: String) : AiOutcome
}

/**
 * Runs an [AiTask] through [RoutingPolicy] and the [HybridEngine], and records it in [HybridLog].
 * Never throws for model problems: failures turn into [AiOutcome.UseFallback].
 */
class HybridAi(
    private val engine: HybridEngine,
    val log: HybridLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun onDeviceState(): OnDeviceState =
        if (!engine.configured) OnDeviceState.UNKNOWN else runCatching { engine.onDeviceState() }.getOrDefault(OnDeviceState.UNKNOWN)

    val configured: Boolean get() = engine.configured

    suspend fun run(task: AiTask, prompt: String, json: Boolean = true): AiOutcome {
        val start = clock()
        val decision = RoutingPolicy.decide(task, engine.configured, onDeviceState())
        if (decision is Decision.UseFallback) return fallback(task, start, decision.fallback, decision.reason)
        val route = (decision as Decision.Run).route
        val raw = try {
            engine.generate(route, prompt, json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fallback(task, start, task.fallback, "Model error: ${e.javaClass.simpleName}")
        }
        if (!RoutingPolicy.accept(task, raw.source)) {
            val leaked = RoutingPolicy.cloudChars(raw.source, prompt)
            return fallback(task, start, task.fallback, "Discarded: private task answered by ${raw.source.badge}", leaked)
        }
        val ms = clock() - start
        val chars = RoutingPolicy.cloudChars(raw.source, prompt)
        log.add(LogEntry(start, task, raw.source, ms, chars, raw.modelVersion ?: ""))
        return AiOutcome.Done(AiResult(raw.text, raw.source, ms, chars))
    }

    private fun fallback(task: AiTask, start: Long, fb: Fallback, reason: String, cloudChars: Int = 0): AiOutcome.UseFallback {
        log.add(LogEntry(start, task, Source.NO_AI, clock() - start, cloudChars, "${fb.name}: $reason"))
        return AiOutcome.UseFallback(fb, reason)
    }
}
