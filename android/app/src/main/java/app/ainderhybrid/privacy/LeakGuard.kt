package app.ainderhybrid.privacy

/**
 * Code-only privacy check for outgoing text (no model). Used on its own when the on-device model
 * is missing, and always in addition to the on-device guard.
 *
 * 1. Private terms: words the operator marked "never share" plus distinctive words from the
 *    private notes (capitalised or containing digits), matched case-insensitively as whole words.
 * 2. Generic PII/secret patterns (e-mail, phone numbers, ID numbers, API keys, private keys).
 * Leaks are reported by category or by the private term itself (shown only on this phone).
 */
object LeakGuard {
    data class Result(val ok: Boolean, val leaks: List<String>)

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "email" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        "phone" to Regex("\\+?\\d[\\d\\s().-]{7,}\\d"),
        "id-number" to Regex("\\d{6}[-\\s]?[1-4]\\d{6}"),
        "api-key" to Regex("(aind_[0-9a-fA-F]{16,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35})"),
        "private-key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "url" to Regex("https?://\\S+"),
    )

    private val STOP = setOf(
        "the", "and", "for", "with", "this", "that", "today", "tomorrow", "yesterday", "monday", "tuesday",
        "wednesday", "thursday", "friday", "saturday", "sunday", "english", "korean", "i'm", "i've", "my",
        "she", "he", "they", "we", "you", "it's", "ai", "but", "also", "then", "when", "what",
    )

    /**
     * Distinctive terms from free-text notes: words with digits or CJK characters, and capitalised
     * words that are not merely the first word of a sentence (those are usually ordinary words).
     */
    fun termsFromNotes(notes: String): List<String> =
        Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}_'-]{2,}").findAll(notes)
            .filter { m ->
                val w = m.value
                if (w.lowercase() in STOP) return@filter false
                if (w.any(Char::isDigit) || w.any { it.code > 0x2E80 }) return@filter true
                w.first().isUpperCase() && !sentenceStart(notes, m.range.first)
            }
            .map { it.value }.distinct().toList()

    private fun sentenceStart(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && text[i].isWhitespace() && text[i] != '\n') i--
        return i < 0 || text[i] in ".!?\n:-•*"
    }

    fun check(text: String, neverShare: List<String>, notes: String): Result {
        val leaks = mutableListOf<String>()
        val terms = (neverShare.map(String::trim).filter { it.length >= 2 } + termsFromNotes(notes)).distinctBy { it.lowercase() }
        for (t in terms) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(t) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            if (re.containsMatchIn(text)) leaks += "private term: $t"
        }
        for ((kind, re) in PATTERNS) if (re.containsMatchIn(text)) leaks += kind
        return Result(leaks.isEmpty(), leaks)
    }
}
