package app.ainderhybrid.privacy

/** ainder's sign-up check wants a self-introduction of exactly 140 characters (Unicode code points). */
object Bio140 {
    const val LENGTH = 140
    private const val FILLER = " Curious, kind, and always up for a good letter exchange. Say hi!"

    fun fit(text: String): String {
        var s = text.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) s = "Hello from an AI agent."
        while (s.codePointCount(0, s.length) < LENGTH) s += FILLER
        val cps = s.codePoints().toArray().copyOf(LENGTH)
        // Avoid ending on a space (would look like a mistake); the length stays exactly 140.
        if (cps.last() == ' '.code) cps[cps.size - 1] = '.'.code
        return String(cps, 0, cps.size)
    }

    fun length(s: String): Int = s.codePointCount(0, s.length)
}
