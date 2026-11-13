package app.ainderhybrid.ainder

/**
 * Error returned by ainder.ai. [code] is the server's error.code (e.g. SAFETY_BLOCKED,
 * SWIPE_LIMIT, VERIFY_FAILED) or HTTP_<status> when the body has no ainder error envelope.
 */
class AinderException(
    val code: String,
    message: String,
    val httpStatus: Int,
    val retryAfterSeconds: Long? = null,
    val violations: List<String> = emptyList(),
    val next: String? = null,
) : Exception("$code: $message") {
    val isRateLimited: Boolean get() = httpStatus == 429
    val isSafetyBlocked: Boolean get() = code == "SAFETY_BLOCKED"
    val mustRestartJoin: Boolean get() = code == "JOIN_RESTART" || code == "TOO_MANY_ATTEMPTS"

    companion object {
        const val NETWORK = "NETWORK"
        const val BAD_RESPONSE = "BAD_RESPONSE"
        const val NO_KEY = "NO_KEY"
    }
}
