package app.ainderhybrid.hybrid

import app.ainderhybrid.ainder.ProfileCard

/** Code rules used when no model may run (Fallback.CODE_RULE). Deterministic, no inference. */
object CompatRule {
    const val LIKE_THRESHOLD = 60

    /** 40 + up to 60 points for shared interests (case-insensitive Jaccard). */
    fun score(me: ProfileCard, them: ProfileCard): Int {
        val a = me.interests.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val b = them.interests.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 40
        val jaccard = a.intersect(b).size.toDouble() / a.union(b).size
        return (40 + jaccard * 60).toInt().coerceIn(0, 100)
    }

    fun likes(score: Int): Boolean = score >= LIKE_THRESHOLD
}
