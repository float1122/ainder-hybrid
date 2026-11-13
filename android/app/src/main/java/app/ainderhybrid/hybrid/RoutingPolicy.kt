package app.ainderhybrid.hybrid

/** State of the on-device model as reported by Firebase AI Logic (OnDeviceModelStatus). */
enum class OnDeviceState { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE, UNKNOWN }

/** Which engine produced a result. NO_AI = human input or a code rule. */
enum class Source(val badge: String) { ON_DEVICE("On-device"), IN_CLOUD("Cloud"), NO_AI("No AI") }

sealed interface Decision {
    data class Run(val route: Route) : Decision
    data class UseFallback(val fallback: Fallback, val reason: String) : Decision
}

/** Pure routing rules. Unit tested in RoutingPolicyTest; this is the privacy boundary of the app. */
object RoutingPolicy {

    init {
        // Invariant: a task that sees private data can only ever be routed ONLY_ON_DEVICE.
        AiTask.entries.forEach { check(it.privacy == Privacy.PUBLIC || it.route == Route.ONLY_ON_DEVICE) { "${it.name} leaks" } }
    }

    fun decide(task: AiTask, aiConfigured: Boolean, onDevice: OnDeviceState): Decision {
        if (!aiConfigured) return Decision.UseFallback(task.fallback, "Firebase AI Logic is not configured in this build")
        return when (task.privacy) {
            Privacy.PRIVATE ->
                if (onDevice == OnDeviceState.AVAILABLE) Decision.Run(Route.ONLY_ON_DEVICE)
                else Decision.UseFallback(task.fallback, "On-device model is ${onDevice.name.lowercase()}; private data stays on this phone")
            Privacy.PUBLIC -> Decision.Run(task.route)
        }
    }

    /**
     * Second line of defence: a PRIVATE task's result is accepted only if it was produced on the
     * device. (ONLY_ON_DEVICE already guarantees this; this check makes a regression visible.)
     */
    fun accept(task: AiTask, source: Source): Boolean =
        task.privacy == Privacy.PUBLIC || source == Source.ON_DEVICE

    /** Characters that left the phone for this call (shown in the Hybrid log). */
    fun cloudChars(source: Source, prompt: String): Int = if (source == Source.IN_CLOUD) prompt.length else 0
}
