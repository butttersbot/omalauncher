package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationSearchTest {
    @Test fun resultsAreSeparateFromMessagesAndKeepOpaqueContinuation() {
        val page = SearchPage.read(JSONObject("""{"hits":[{"id":"OLD_2","at":4,"fromMe":false,"sender":"+1555","senderName":"Ana","snippet":"old needle"}],"nextCursor":"opaque,not-time","complete":false}"""))
        assertEquals("OLD_2", page.hits.single().id)
        assertEquals("Ana", page.hits.single().sender)
        assertEquals("opaque,not-time", page.nextCursor)
        assertFalse(page.complete)
    }
    @Test fun contextNeverMatchesUnrelatedMessagesByWords() {
        val local = Message("same", 1, "", id = "c:pending")
        val native = Message("same", 1, "", id = "UNRELATED")
        assertEquals(2, ThreadSlice.merge(listOf(local), listOf(native)).size)
        assertEquals(1, ThreadSlice.merge(listOf(local), listOf(native.copy(clientId = "pending"))).size)
    }
    @Test fun contextFactsRetainStableIdentityAndUnsendCannotBeResurrected() {
        val original = Message("secret", 1, "Ana", id = "OLD_2", uiKey = "stable")
        val unsent = original.copy(text = "", files = emptyList(), unsent = true, uiKey = null)
        val merged = ThreadSlice.merge(listOf(original), listOf(unsent))
        assertEquals("stable", merged.single().renderKey)
        assertTrue(ThreadSlice.merge(merged, listOf(original)).single().unsent)
        assertEquals("", ThreadSlice.merge(merged, listOf(original)).single().text)
        val cached = Message("reply", 100, "", id = "NEW", quote = "Ana: secret", replyTo = "OLD_2")
        val book = ConversationBook(mapOf("chat" to Conversation("chat", "Ana", false, listOf(cached), 0)))
            .quoteFacts("chat", listOf(unsent))
        assertEquals(listOf("NEW"), book["chat"]!!.messages.map { it.id })
        assertEquals("Message was unsent", book["chat"]!!.messages.single().quote)
    }
}
