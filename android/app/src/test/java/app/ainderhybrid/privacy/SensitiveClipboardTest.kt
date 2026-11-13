package app.ainderhybrid.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveClipboardTest {
    @Test fun `key clip is marked sensitive with the platform extra and cleared after a minute`() {
        assertEquals("android.content.extra.IS_SENSITIVE", SensitiveClipboard.EXTRA_IS_SENSITIVE)
        assertEquals(60_000L, SensitiveClipboard.CLEAR_AFTER_MS)
    }

    @Test fun `only our own sensitive clip is cleared`() {
        assertTrue(SensitiveClipboard.isOurClip(SensitiveClipboard.LABEL, sensitive = true))
        assertFalse("user copied something else", SensitiveClipboard.isOurClip("note", sensitive = false))
        assertFalse("same label but not sensitive", SensitiveClipboard.isOurClip(SensitiveClipboard.LABEL, sensitive = false))
        assertFalse(SensitiveClipboard.isOurClip(null, sensitive = true))
    }
}
