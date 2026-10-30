package dev.omalauncher.messages.engine

import org.junit.Assert.*
import org.junit.Test

class TimelineIdentityTest {
    private fun book() = ConversationBook().fromMac("chat", "Ana", false, emptyList())

    @Test fun pendingEchoAndLaterReceiptKeepTheSameRowAfterRestart() {
        val pending = book().waiting("chat", "client", "hello", 1000)
        val key = pending["chat"]!!.messages.single().renderKey
        val echoed = pending.fromMac("chat", null, null, listOf(Message("hello", 1010, "", id = "server", clientId = "client")))
        assertEquals(1, echoed["chat"]!!.messages.size)
        assertEquals(key, echoed["chat"]!!.messages.single().renderKey)
        val restored = ConversationBook.fromJson(echoed.toJson())
        val updated = restored.fromMac("chat", null, null, listOf(Message("hello edited", 1010, "", id = "server")), counting = false)
        assertEquals(key, updated["chat"]!!.messages.single().renderKey)
    }

    @Test fun savedReadingAnchorSurvivesHistoryPrependingAndRoundTrip() {
        val a = Message("older", 1, "Ana", id = "a")
        val b = Message("reading", 2, "Ana", id = "b")
        val left = Left(index = 0, offset = 31, count = 1, anchor = b.renderKey)
        val restored = Left.fromJson(Left.toJson(mapOf("chat" to left))).getValue("chat")
        assertEquals(1, restored.indexIn(listOf(a, b)))
        assertEquals(31, restored.offset)
        assertEquals(0, Left(index = 0).indexIn(listOf(a, b)))
    }
    @Test fun removedPreviewStaysRemovedAcrossEchoRestartAndReceipt() {
        val url = "https://example.com/story"
        val pending = book().waiting("chat", "c", url, 1, hiddenLinks = setOf(url))
        val echo = Message(url, 2, "", id = "s", clientId = "c", richLinks = listOf(RichLink(url, title = "Story")))
        val restored = ConversationBook.fromJson(pending.fromMac("chat", null, null, listOf(echo)).toJson())
        val updated = restored.fromMac("chat", null, null, listOf(echo.copy(clientId = null, delivery = Delivery.Read)))
        assertEquals(setOf(url), updated["chat"]!!.messages.single().hiddenLinks)
        assertTrue(shared(*updated.conversations.toTypedArray()).all { it.richLinks.isEmpty() })
    }
}
