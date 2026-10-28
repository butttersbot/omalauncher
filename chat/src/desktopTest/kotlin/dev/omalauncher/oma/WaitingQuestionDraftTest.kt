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
    @Test fun skipNeedsNoSelectionsAndSharesTheSendGateWithTheForm() {
        var calls = 0
        var callback: ((String?) -> Unit)? = null
        var closed = 0
        val request = WaitingQuestion("nonce", "agent", """{"questions":[{"question":"Layout?","isOtherAllowed":false}]}""") { payload, done ->
            calls++
            assertTrue(dev.omalauncher.assist.questionAnswersSkipped(payload))
            callback = done
        }
        val draft = WaitingQuestionDraft()
        draft.others = mapOf("Layout?" to "unsent draft")
        draft.skip(request) { closed++ }
        draft.skip(request)
        assertEquals(1, calls)
        callback!!("offline")
        assertFalse(draft.sent)
        assertEquals("unsent draft", draft.others["Layout?"])
        draft.skip(request) { closed++ }
        callback!!(null)
        draft.skip(request)
        assertEquals(2, calls)
        assertEquals(1, closed)
        assertTrue(draft.sent)
        assertTrue(draft.skipped)
        assertTrue(WaitingQuestionDraft.restore(draft.json()).skipped)
    }
    @Test fun remoteWithdrawalClosesUnansweredFormButOwnNormalSendReceiptStays() {
        val draft = WaitingQuestionDraft()
        assertFalse(draft.closeAfterWithdrawal(true))
        assertTrue(draft.closeAfterWithdrawal(false))
        draft.sending = true
        assertFalse(draft.closeAfterWithdrawal(false))
        draft.sending = false; draft.sent = true
        assertFalse(draft.closeAfterWithdrawal(false))
        draft.skipped = true
        assertTrue(draft.closeAfterWithdrawal(false))
    }
}
