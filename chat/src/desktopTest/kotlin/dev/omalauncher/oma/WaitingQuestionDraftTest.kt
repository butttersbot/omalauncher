package dev.omalauncher.oma

import org.junit.Assert.*
import org.junit.Test

class WaitingQuestionDraftTest {
    @Test fun draftAndSendGateSurviveChangingPanelOwnerAndSavedRestoration() {
        val workspace = WaitingQuestionDrafts()
        val cover = workspace.get("ticket:nonce")
        cover.answers = mapOf("Screens?" to listOf("Cover", "Unfolded"))
        cover.others = mapOf("Layout?" to "Beside chat\n日本語")
        cover.sending = true
        val sidePanel = workspace.get("ticket:nonce")
        assertSame(cover, sidePanel)
        assertEquals(listOf("Cover", "Unfolded"), sidePanel.answers["Screens?"])
        assertEquals("Beside chat\n日本語", sidePanel.others["Layout?"])
        assertTrue(sidePanel.sending)

        val restored = WaitingQuestionDrafts.restore(workspace.json()).get("ticket:nonce")
        assertEquals(cover.answers, restored.answers)
        assertEquals(cover.others, restored.others)
        assertTrue(restored.sending)
        assertFalse(restored.sent)
        assertTrue(workspace.get("ticket:new-nonce").answers.isEmpty())
    }
}
