package dev.omalauncher.messages.engine

import org.junit.Assert.*
import org.junit.Test

class ChatChoicesTest {
    private fun chat(id: String, at: Long) = Conversation(id, id, false, listOf(Message(id, at, "Ana")), 2)

    @Test fun pinsKeepTheirOrderAcrossIncomingMessagesAndUnpinRestoresRecency() {
        val choices = LocalChatChoices().pin("old", true).pin("other", true)
        val chats = listOf(chat("new", 100), chat("other", 20), chat("old", 1))
        assertEquals(listOf("old", "other", "new"), choices.order(chats).map { it.id })
        val afterArrival = chats.map { if (it.id == "other") chat("other", 200) else it }
        assertEquals(listOf("old", "other", "new"), choices.order(afterArrival).map { it.id })
        assertEquals(listOf("other", "new", "old"), choices.pin("old", false).order(afterArrival).map { it.id })
    }

    @Test fun muteAndPinSurviveRestartWithoutChangingUnreadOrMessages() {
        val chat = chat("a", 1)
        val restored = LocalChatChoices.read(LocalChatChoices().pin("a", true).mute("a", true).json())
        assertEquals(listOf("a"), restored.pinned)
        assertTrue("a" in restored.muted)
        assertSame(chat, restored.order(listOf(chat)).single())
        assertEquals(2, chat.unread)
        assertEquals(listOf("a"), restored.mute("a", false).pinned)
        assertFalse("a" in restored.mute("a", false).muted)
    }

    @Test fun temporarilyUnloadedPinnedChatReturnsInItsOriginalPosition() {
        val restored = LocalChatChoices.read("""{"pinned":["missing","a","a"],"muted":["a"],"futureField":true}""")
        assertEquals(listOf("a"), restored.order(listOf(chat("a", 10))).map { it.id })
        assertEquals(listOf("missing", "a"), restored.order(listOf(chat("a", 10), chat("missing", 1))).map { it.id })
        assertEquals(LocalChatChoices(), LocalChatChoices.read("broken"))
    }
    @Test fun ninthPinIsStableAndUnpinFreesAPlaceAfterRestart() {
        val all = (1..9).fold(LocalChatChoices()) { c, i -> c.pin("$i", true) }
        assertEquals(all, all.pin("1", true))
        assertEquals(all, all.pin("10", true))
        val restored = LocalChatChoices.read(all.pin("3", false).pin("10", true).json())
        assertEquals(listOf("1", "2", "4", "5", "6", "7", "8", "9", "10"), restored.pinned)
    }
}
