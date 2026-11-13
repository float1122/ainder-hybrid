package app.ainderhybrid.privacy

import app.ainderhybrid.hybrid.JsonExtract
import app.ainderhybrid.hybrid.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {

    @Test fun `bio140 is exactly 140 code points for short, long and emoji input`() {
        listOf("", "Hi", "x".repeat(500), "🙂".repeat(10) + " tea lover", "  spaced   out  ").forEach {
            val b = Bio140.fit(it)
            assertEquals(it, 140, Bio140.length(b))
            assertFalse(b.endsWith(" "))
        }
    }

    @Test fun `leak guard catches never-share terms case-insensitively as whole words`() {
        val r = LeakGuard.check("I love quokkasoft culture", listOf("Quokkasoft"), "")
        assertFalse(r.ok)
        assertTrue(LeakGuard.check("Danalysis is fine", listOf("Dana"), "").ok)
    }

    @Test fun `leak guard catches generic PII and key shapes`() {
        assertFalse(LeakGuard.check("write to " + "a" + "@" + "b.co", emptyList(), "").ok)
        assertFalse(LeakGuard.check("call +82 10 1234 5678", emptyList(), "").ok)
        assertFalse(LeakGuard.check("key " + "aind_" + "a".repeat(20), emptyList(), "").ok)
        assertTrue(LeakGuard.check("Tea and patience fixed my day.", emptyList(), "").ok)
    }

    @Test fun `terms from notes skip sentence-initial words but keep names`() {
        val terms = LeakGuard.termsFromNotes("Went to the office. Met Dana at Quokkasoft in Busan. Release R2D2 shipped.")
        assertTrue(terms.containsAll(listOf("Dana", "Quokkasoft", "Busan", "R2D2")))
        assertFalse(terms.contains("Went"))
        assertFalse(terms.contains("Met"))
        assertFalse(terms.contains("Release"))
    }

    @Test fun `json extract tolerates fences and chatter`() {
        assertEquals("1", JsonExtract.obj("Sure! ```json\n{\"a\": 1}\n``` done")!!["a"].toString())
        assertNull(JsonExtract.obj("no json here"))
    }

    @Test fun `prompt fill replaces known and blanks unknown placeholders`() {
        assertEquals("Hi Nova, !", Prompts.fill("Hi {{name}}, {{missing}}!", mapOf("name" to "Nova")))
    }
}
