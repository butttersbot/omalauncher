package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RepliesTest {
    @Test fun exactPartSurvivesRestoredDraftAndNoTargetIsInventedForLegacyQuote() {
        val draft = Left(draft = "yes", quote = "Ana: photo", replyTo = "GUID_2")
        val restored = Left.fromJson(Left.toJson(mapOf("chat" to draft))).getValue("chat")
        assertEquals("GUID_2", Replies.target(restored.replyTo))
        assertEquals(draft, restored)
        val targetOnly = Left(replyTo = "GUID_2")
        assertEquals(targetOnly, Left.fromJson(Left.toJson(mapOf("chat" to targetOnly)))["chat"])
        val legacy = Left.fromJson("""{"left":{"chat":{"draft":"yes","quote":"Ana: photo"}}}""").getValue("chat")
        assertTrue(Replies.missing(legacy.quote, Replies.target(legacy.replyTo)))
    }

    @Test fun localTargetWaitsForItsExactEchoAndMultipartIdentityIsKept() {
        assertNull(Replies.target("c:pending"))
        val echo = Message("identical words", 1, "", id = "GUID_2", uiKey = "c:pending")
        val other = Message("identical words", 1, "", id = "WRONG")
        assertEquals("GUID_2", Replies.target("c:pending", listOf(other, echo)))
        assertEquals("GUID_2", Replies.target("GUID_2#a"))
        assertNull(Replies.target("GUID\r\nInjected: yes"))
    }

    @Test fun targetSurvivesWaitingEchoUpdateAndHistoryRestore() {
        var book = ConversationBook().fromMac("chat", "Ana", false, emptyList())
            .waiting("chat", "client", "reply", 2, quote = "Ana: photo", replyTo = "PHOTO_3")
        val echo = Message("reply", 2, "", id = "SERVER", clientId = "client")
        book = book.fromMac("chat", null, null, listOf(echo))
            .fromMac("chat", null, null, listOf(echo.copy(clientId = null)))
        val restored = ConversationBook.fromJson(book.toJson())["chat"]!!.messages.single()
        assertEquals("PHOTO_3", restored.replyTo)
        assertEquals("Ana: photo", restored.quote)
        assertEquals("c:client", restored.renderKey)
        val incoming = MacWire.msg(JSONObject("""{"id":"incoming","chatId":"chat","text":"yes","at":3,"replyTo":"PHOTO_3"}"""))
        assertEquals("PHOTO_3", MacWire.toMessages(incoming).single().replyTo)
    }

    @Test fun threadMergesUseIdsAndNeverLabelAnUncoveredGapComplete() {
        val root = Message("same words", 1, "Ana", id = "ROOT_2#a")
        val child = Message("same words", 2, "Ana", id = "CHILD", replyTo = "ROOT_2")
        val nested = Message("reply", 3, "", id = "c:local", replyTo = "CHILD")
        val other = Message("same words", 2, "Ana", id = "OTHER")
        assertEquals(listOf(root, child, nested), Replies.members("ROOT_2", listOf(root, child, nested, other)))
        val loaded = ThreadSlice("ROOT_2", listOf(root, child), null, true, false, 0)
        val newest = Message("new", 4, "Ana", id = "NEW", replyTo = "ROOT_2")
        val overlap = ThreadSlice("ROOT_2", listOf(root, child, newest), "scan-head", false, false, 0)
        assertTrue(loaded.refreshing(overlap).complete)
        assertNull(loaded.refreshing(overlap).nextCursor)
        val gap = overlap.copy(messages = listOf(root, newest))
        assertFalse(loaded.refreshing(gap).complete)
        assertEquals("scan-head", loaded.refreshing(gap).nextCursor)
        assertNotEquals(Replies.draftKey("a:b", "c"), Replies.draftKey("a", "b:c"))
    }
}
