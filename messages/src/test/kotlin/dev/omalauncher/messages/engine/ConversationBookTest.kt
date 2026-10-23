package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBookTest {
    private fun mina(vararg messages: SeenMessage) = SeenConversation("chat-mina", "Mina Park", false, messages.toList())

    @Test fun aRepostedNotificationAddsOnlyWhatIsNew() {
        val first = ConversationBook().take(mina(SeenMessage("are we still on for friday?", 1_000, "Mina")))
        val again = first.take(mina(SeenMessage("are we still on for friday?", 1_000, "Mina"), SeenMessage("the counter!!", 2_000, "Mina")))
        val c = again["chat-mina"]!!
        assertEquals(listOf("are we still on for friday?", "the counter!!"), c.messages.map { it.text })
        assertEquals(2, c.unread)
    }

    @Test fun theOwnersOwnMessagesAreNeverUnread() {
        val c = ConversationBook().take(mina(SeenMessage("Yes! Friday 7:30", 1_000, null), SeenMessage("ok you win", 2_000, "Mina")))["chat-mina"]!!
        assertTrue(c.messages.first().fromMe)
        assertEquals(1, c.unread)
    }

    @Test fun aMessageSentHereIsMatchedToOpenBubblesCopy() {
        val book = ConversationBook().take(mina(SeenMessage("bring it?", 1_000, "Mina"))).sent("chat-mina", "bring it", 5_000)
        assertTrue(book["chat-mina"]!!.messages.last().sentHere)
        // OpenBubbles reposts the conversation with the reply in it, a moment later by its own clock.
        val echoed = book.take(mina(SeenMessage("bring it?", 1_000, "Mina"), SeenMessage("bring it", 5_800, null)))
        val messages = echoed["chat-mina"]!!.messages
        assertEquals(2, messages.size)
        assertEquals(Message("bring it", 5_800, ""), messages.last())
    }

    @Test fun theSameWordsLaterAreANewMessage() {
        val book = ConversationBook().take(mina(SeenMessage("hi", 1_000, "Mina"))).sent("chat-mina", "ok", 2_000)
        val later = book.take(mina(SeenMessage("ok", 2_000 + ConversationBook.ECHO_MS + 1, null)))
        assertEquals(listOf("hi", "ok", "ok"), later["chat-mina"]!!.messages.map { it.text })
    }

    @Test fun readingClearsUnreadAndTheListIsNewestFirst() {
        val book =
            ConversationBook()
                .take(mina(SeenMessage("one", 1_000, "Mina")))
                .take(SeenConversation("chat-tahoe", "Tahoe trip", true, listOf(SeenMessage("who's on groceries", 3_000, "Ollie"))))
        assertEquals(listOf("chat-tahoe", "chat-mina"), book.conversations.map { it.id })
        assertEquals(0, book.read("chat-tahoe")["chat-tahoe"]!!.unread)
        assertNull(book.read("nobody")["nobody"])
    }

    @Test fun aBlankTitleKeepsTheOneAlreadyKnown() {
        val book = ConversationBook().take(mina(SeenMessage("hi", 1_000, "Mina"))).take(SeenConversation("chat-mina", "", false, emptyList()))
        assertEquals("Mina Park", book["chat-mina"]!!.title)
    }

    @Test fun theBookSurvivesBeingSavedAndAGarbledFileIsEmpty() {
        val book =
            ConversationBook()
                .take(mina(SeenMessage("are we still on?", 1_000, "Mina"), SeenMessage("yes", 2_000, null)))
                .sent("chat-mina", "see you", 3_000)
        val back = ConversationBook.fromJson(book.toJson())
        assertEquals(book["chat-mina"], back["chat-mina"])
        assertTrue(ConversationBook.fromJson("{not json").conversations.isEmpty())
    }

    @Test fun aQuotedReplyKeepsItsQuoteThroughTheEchoAndASave() {
        val book = ConversationBook().take(mina(SeenMessage("bring the sake?", 1_000, "Mina"))).sent("chat-mina", "bring it", 5_000, quote = "Mina: bring the sake?")
        val echoed = book.take(mina(SeenMessage("bring it", 5_500, null)))
        assertEquals("Mina: bring the sake?", echoed["chat-mina"]!!.messages.last().quote)
        assertEquals(echoed["chat-mina"], ConversationBook.fromJson(echoed.toJson())["chat-mina"])
        assertNull(ConversationBook.fromJson(echoed.toJson())["chat-mina"]!!.messages.first().quote)
    }

    @Test fun aHandOffWaitsUntilOpenBubblesShowsItLater() {
        val book = ConversationBook().take(mina(SeenMessage("pics?", 1_000, "Mina"))).handedOff("chat-mina", "2 Photos", 10_000)
        assertTrue(book["chat-mina"]!!.messages.last().handedOff)
        // OpenBubbles reposts the conversation hours later, when Mina writes again.
        val later = 10_000 + 3 * 60 * 60_000L
        val echoed = book.take(mina(SeenMessage("2 Photos", later, null), SeenMessage("cute", later + 5, "Mina")))
        val messages = echoed["chat-mina"]!!.messages
        assertEquals(listOf("pics?", "2 Photos", "cute"), messages.map { it.text })
        assertEquals(false, messages[1].handedOff)
        assertEquals(echoed["chat-mina"], ConversationBook.fromJson(echoed.toJson())["chat-mina"])
    }

    @Test fun aHandOffSurvivesASaveAndCanBeTakenBack() {
        val book = ConversationBook().take(mina(SeenMessage("pics?", 1_000, "Mina"))).handedOff("chat-mina", "1 Photo", 2_000)
        val back = ConversationBook.fromJson(book.toJson())
        assertTrue(back["chat-mina"]!!.messages.last().handedOff)
        assertEquals(listOf("pics?"), back.drop("chat-mina", back["chat-mina"]!!.messages.last())["chat-mina"]!!.messages.map { it.text })
    }

    @Test fun theMacsMessagesMergeByTheirIds() {
        val m1 = Message("are we still on?", 1_000, "Mina", id = "m1")
        var book = ConversationBook().fromMac("c1", "Mina", false, listOf(m1), unread = 1)
        // The same message again (the stream after the chat list) is not a second one.
        book = book.fromMac("c1", null, null, listOf(m1, Message("the counter!!", 2_000, "Mina", id = "m2")))
        val c = book["c1"]!!
        assertEquals(listOf("are we still on?", "the counter!!"), c.messages.map { it.text })
        assertEquals(2, c.unread)
        assertEquals("Mina", c.title)
        // History counts nothing as unread.
        val older = book.fromMac("c1", null, null, listOf(Message("hi", 500, "Mina", id = "m0")), counting = false)["c1"]!!
        assertEquals(2, older.unread)
        assertEquals("hi", older.messages.first().text)
    }

    @Test fun aMessageSentThroughTheMacGivesWayToTheMacsCopy() {
        var book = ConversationBook().fromMac("c1", "Mina", false, listOf(Message("bring it?", 1_000, "Mina", id = "m1")), unread = 0)
        book = book.waiting("c1", "k1", "1 Photo", 5_000, quote = "Mina: bring it?").waiting("c1", "k1", "here", 5_001)
        assertEquals(listOf("c:k1", "c:k1"), book["c1"]!!.messages.drop(1).map { it.id })
        // The Mac shows each back as it went: the photo, then the words, by the Mac's own clock.
        book = book.fromMac("c1", null, null, listOf(Message("1 Photo", 5_400, "", id = "m2#a")))
        book = book.fromMac("c1", null, null, listOf(Message("here", 5_900, "", id = "m3")))
        val c = book["c1"]!!
        assertEquals(listOf("m1", "m2#a", "m3"), c.messages.map { it.id })
        assertEquals("Mina: bring it?", c.messages[1].quote)
        assertEquals(0, c.unread)
        // The same words from someone else, or long after, are a message of their own.
        val other = book.waiting("c1", "k2", "ok", 10_000).fromMac("c1", null, null, listOf(Message("ok", 10_100, "Mina", id = "m4")))
        assertEquals(listOf("c:k2", "m4"), other["c1"]!!.messages.takeLast(2).map { it.id })
    }

    @Test fun anUndoneMessageGoesWithItsAttachments() {
        val book = ConversationBook().fromMac("c1", "Mina", false, listOf(Message("1 Photo", 1, "", id = "m1#a"), Message("look", 1, "", id = "m1"), Message("hi", 2, "", id = "m2")), unread = 0)
        assertEquals(listOf("m2"), book.removed("c1", listOf("m1"))["c1"]!!.messages.map { it.id })
    }

    @Test fun aSendTheMacRefusedIsMarkedAndSurvivesSaving() {
        val book = ConversationBook().fromMac("c1", "Mina", false, emptyList(), unread = 0).waiting("c1", "k1", "hello", 5_000).failed("c1", "k1")
        val m = ConversationBook.fromJson(book.toJson())["c1"]!!.messages.single()
        assertTrue(m.failed)
        assertEquals("c:k1", m.id)
        assertTrue(book.without("c1", m)["c1"]!!.messages.isEmpty())
    }
}
