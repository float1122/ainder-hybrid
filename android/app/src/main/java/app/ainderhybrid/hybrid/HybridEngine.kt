package app.ainderhybrid.hybrid

/** Raw model output before routing checks. */
data class RawResult(val text: String, val source: Source, val modelVersion: String?)

/** Seam between the routing logic and the Firebase AI Logic SDK (faked in unit tests). */
interface HybridEngine {
    val configured: Boolean
    suspend fun onDeviceState(): OnDeviceState
    suspend fun generate(route: Route, prompt: String, json: Boolean): RawResult
}
