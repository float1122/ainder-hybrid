package app.ainderhybrid.hybrid

/** Where a task may run. Mirrors Firebase AI Logic's InferenceMode values we use. */
enum class Route { PREFER_ON_DEVICE, ONLY_ON_DEVICE, PREFER_IN_CLOUD }

/** Whether the task's prompt contains data that must never leave the phone. */
enum class Privacy { PUBLIC, PRIVATE }

/** What the app does when no model can run the task. None of these use inference. */
enum class Fallback {
    /** The human types or picks the result. */
    HUMAN,
    /** A deterministic code rule decides (no model). */
    CODE_RULE,
    /** The step is skipped; nothing is lost (data can be re-read from ainder). */
    SKIP,
}

/**
 * Every AI task in the app (letters A–H in README "How Firebase AI Logic hybrid inference is used").
 * PRIVATE tasks see operator notes, persona or memory and are ONLY_ON_DEVICE: if the on-device
 * model is missing they fall back to a human or a code rule, never to the cloud.
 */
enum class AiTask(val label: String, val privacy: Privacy, val route: Route, val fallback: Fallback, val prompt: String) {
    QUIZ("Sign-up quiz", Privacy.PUBLIC, Route.PREFER_ON_DEVICE, Fallback.HUMAN, "quiz"),
    CARD("Public card draft", Privacy.PRIVATE, Route.ONLY_ON_DEVICE, Fallback.HUMAN, "card"),
    BRIEF("Safe brief", Privacy.PRIVATE, Route.ONLY_ON_DEVICE, Fallback.HUMAN, "brief"),
    JUDGE("Compatibility", Privacy.PUBLIC, Route.PREFER_IN_CLOUD, Fallback.CODE_RULE, "judge"),
    RERANK("Like or pass", Privacy.PRIVATE, Route.ONLY_ON_DEVICE, Fallback.CODE_RULE, "rerank"),
    LETTER("Letter", Privacy.PUBLIC, Route.PREFER_IN_CLOUD, Fallback.HUMAN, "letter"),
    GUARD("Privacy guard", Privacy.PRIVATE, Route.ONLY_ON_DEVICE, Fallback.CODE_RULE, "guard"),
    MEMORY("Memory", Privacy.PRIVATE, Route.ONLY_ON_DEVICE, Fallback.SKIP, "memory"),
}
