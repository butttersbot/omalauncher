package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedTest {
    private val mina =
        Conversation(
            "chat-mina", "Mina Park", false,
            listOf(
                Message("look https://kiriko.jp/menu", 1_000, "Mina"),
                Message("are we still on?", 2_000, "Mina"),
                Message("1 Photo", 3_000, ""),
                Message("again kiriko.jp/menu? no, https://kiriko.jp/menu", 4_000, ""),
            ),
            0,
        )
    private val tahoe = Conversation("chat-tahoe", "Tahoe trip", true, listOf(Message("www.airbnb.com/rooms/1", 2_500, "Ollie")), 0)

    @Test fun linksAndAttachmentsNewestFirstEachOnce() {
        val items = shared(mina)
        assertEquals(listOf("https://kiriko.jp/menu", "1 Photo"), items.map { (it.part as? Part.Link)?.url ?: (it.part as Part.Attachment).summary })
        assertEquals(listOf("You", "You"), items.map { it.sender })
    }

    @Test fun acrossChatsKeepsWhereEachCameFrom() {
        val items = shared(mina, tahoe)
        assertEquals(listOf("chat-mina", "chat-mina", "chat-tahoe"), items.map { it.conversation })
        assertEquals("Ollie", items.last().sender)
    }
}
