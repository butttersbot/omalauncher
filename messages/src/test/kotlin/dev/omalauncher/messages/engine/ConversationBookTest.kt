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
        // What a message stands for survives saving too.
        val photo = ConversationBook().fromMac("c2", "Ben", false, listOf(Message("1 Photo", 1, "Ben", id = "p#a", files = listOf("A1|image/heic"))), unread = 0)
        assertEquals(listOf("A1|image/heic"), ConversationBook.fromJson(photo.toJson())["c2"]!!.messages.single().files)
    }

    // Boss, 2026-10-23: "Not sent" stayed on messages that had gone (the Mac asleep, the phone timed out, then sent).
    @Test fun aSendMarkedFailedThatWentAfterAllIsReplacedByTheMacsCopy() {
        val start = ConversationBook().fromMac("c1", "Mina", false, emptyList(), unread = 0)
        val byId = start.waiting("c1", "k1", "on my way", 5_000).failed("c1", "k1")
            .fromMac("c1", null, null, listOf(Message("on my way", 600_000, "", id = "m9", clientId = "k1")))["c1"]!!
        assertEquals(listOf("m9"), byId.messages.map { it.id })
        assertTrue(byId.messages.none { it.failed })
        // Without the client id echoed: the same words within half an hour.
        val byWords = start.waiting("c1", "k2", "running late", 5_000).failed("c1", "k2")
            .fromMac("c1", null, null, listOf(Message("running late", 900_000, "", id = "m10")))["c1"]!!
        assertEquals(listOf("m10"), byWords.messages.map { it.id })
        // Much later, the same words are a new message, and the one that failed stays failed.
        val later = start.waiting("c1", "k3", "ok", 5_000).failed("c1", "k3")
            .fromMac("c1", null, null, listOf(Message("ok", 5_000 + 3_600_000, "", id = "m11")))["c1"]!!
        assertEquals(2, later.messages.size)
        assertTrue(later.messages.first().failed)
    }

    @Test fun openBubblesCopiesGoOnceLinked() {
        // The Mac's Kel, and OpenBubbles' Kel from a notification (no Mac ids): only the Mac's stays.
        val book =
            ConversationBook()
                .fromMac("iMessage;-;+1555", "Kel", false, listOf(Message("cutest thing", 5, "Kel", id = "m1")), unread = 1)
                .take(SeenConversation("ob-kel", "Kel", false, listOf(SeenMessage("cutest thing", 5, "Kel"))))
        assertEquals(2, book.conversations.size)
        val only = book.fromMacOnly()
        assertEquals(listOf("iMessage;-;+1555"), only.conversations.map { it.id })
        // Nothing to drop: the same book.
        assertTrue(only.fromMacOnly() === only)
    }

    // Boss, 2026-10-24: his own messages put a red dot on the conversation. The Mac counts its unread sends too.
    @Test fun theOwnersMessagesAloneNeverMakeUnread() {
        // First seen with only his own message last, the Mac's count (it counted the send) is not a dot.
        var book = ConversationBook().fromMac("c1", "Mina", false, listOf(Message("on my way", 1_000, "", id = "m1")), unread = 1)
        assertEquals(0, book["c1"]!!.unread)
        // Sent from here, the Mac's copy and its chat update after it: still nothing unread.
        book = book.waiting("c1", "k1", "5 min", 2_000)
        book = book.fromMac("c1", null, null, listOf(Message("5 min", 2_100, "", id = "m2", clientId = "k1")))
        book = book.fromMac("c1", "Mina", false, listOf(Message("5 min", 2_100, "", id = "m2")), unread = 2)
        assertEquals(0, book["c1"]!!.unread)
    }

    @Test fun anOutgoingMessageNeitherClearsNorAddsToUnread() {
        var book = ConversationBook().fromMac("c1", "Mina", false, listOf(Message("are we still on?", 1_000, "Mina", id = "m1")), unread = 1)
        book = book.fromMac("c1", null, null, listOf(Message("the counter!!", 1_500, "Mina", id = "m2")))
        assertEquals(2, book["c1"]!!.unread)
        // He answers (from the Mac, another device, or here): the echo and the Mac's count for it, 0 or 3, change nothing.
        book = book.fromMac("c1", null, null, listOf(Message("yes!", 2_000, "", id = "m3")))
        assertEquals(2, book["c1"]!!.unread)
        assertEquals(2, book.fromMac("c1", null, null, listOf(Message("yes!", 2_000, "", id = "m3")), unread = 0)["c1"]!!.unread)
        assertEquals(2, book.fromMac("c1", null, null, listOf(Message("yes!", 2_000, "", id = "m3")), unread = 3)["c1"]!!.unread)
        // Reading clears it.
        assertEquals(0, book.read("c1")["c1"]!!.unread)
    }

    @Test fun theMacsCountLowersButNeverRaisesPastWhatCameIn() {
        var book = ConversationBook().fromMac("c1", "Mina", false, listOf(Message("hi", 1_000, "Mina", id = "m1")), unread = 1)
        // A new message from her, with the Mac counting his sends as well: one more, not the Mac's four.
        book = book.fromMac("c1", "Mina", false, listOf(Message("you there?", 2_000, "Mina", id = "m2")), unread = 4)
        assertEquals(2, book["c1"]!!.unread)
        // Read on the Mac: its 0 clears the count here too.
        book = book.fromMac("c1", "Mina", false, listOf(Message("you there?", 2_000, "Mina", id = "m2")), unread = 0)
        assertEquals(0, book["c1"]!!.unread)
        // A message from her after reading counts again.
        book = book.fromMac("c1", null, null, listOf(Message("ok then", 3_000, "Mina", id = "m3")))
        assertEquals(1, book["c1"]!!.unread)
    }

    @Test fun aSavedDotOnlyTheOwnersOwnMessagesMadeIsCleared() {
        // Saved by 0.8.1: unread on a conversation with nothing from anyone else in it.
        val legacy =
            ConversationBook().fromMac("c1", "Mina", false, listOf(Message("on my way", 1_000, "Mina", id = "m0")), unread = 1)
                .read("c1").fromMac("c2", "Ben", false, listOf(Message("sent", 1_000, "", id = "m1")), unread = 0)
        val json = legacy.toJson().replace("\"unread\":0", "\"unread\":3")
        val loaded = ConversationBook.fromJson(json)
        assertEquals(0, loaded["c2"]!!.unread)
        // Where someone else wrote, the saved count stays: nothing of theirs is marked read.
        assertEquals(3, loaded["c1"]!!.unread)
    }
}
