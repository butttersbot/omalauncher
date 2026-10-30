package dev.omalauncher.messages.engine

import org.junit.Assert.*
import org.junit.Test

class LinkPreviewSelectionTest {
    @Test fun exactVisibleLinkAndRemovedPreviewSurviveWireSelection() {
        val one = "https://x.com/example/status/123"
        val two = "https://example.com/story"
        assertEquals(one, linkPreviewSelection("Look $one", emptySet())!!.getString("url"))
        assertTrue(linkPreviewSelection("Look $one", setOf(one))!!.getBoolean("disabled"))
        assertEquals(two, linkPreviewSelection("$one $two", setOf(one))!!.getString("url"))
        assertNull(linkPreviewSelection("ordinary words", emptySet()))
        assertNull(linkPreviewSelection("https://user:pass@example.com/story", emptySet()))
        assertNull(linkPreviewSelection("https://example.com/" + "a".repeat(5000), emptySet()))
    }
}
