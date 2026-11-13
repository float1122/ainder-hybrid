package app.ainderhybrid.hybrid

/** Records every prompt and the route it was sent with; answers from a script. */
class FakeEngine(
    override val configured: Boolean = true,
    var onDevice: OnDeviceState = OnDeviceState.AVAILABLE,
    /** Which source the fake model reports for a route (simulates what the SDK would return). */
    var sourceFor: (Route) -> Source = { r -> if (r == Route.PREFER_IN_CLOUD) Source.IN_CLOUD else Source.ON_DEVICE },
    var answer: (Route, String) -> String = { _, _ -> "{}" },
) : HybridEngine {
    data class Call(val route: Route, val prompt: String)
    val calls = mutableListOf<Call>()
    var throwOnGenerate: Exception? = null

    override suspend fun onDeviceState() = onDevice

    override suspend fun generate(route: Route, prompt: String, json: Boolean): RawResult {
        calls += Call(route, prompt)
        throwOnGenerate?.let { throw it }
        return RawResult(answer(route, prompt), sourceFor(route), "fake-model")
    }

    /** Prompts that could have reached the cloud (any route other than ONLY_ON_DEVICE). */
    fun cloudCapablePrompts(): List<String> = calls.filter { it.route != Route.ONLY_ON_DEVICE }.map { it.prompt }
}
