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
}
