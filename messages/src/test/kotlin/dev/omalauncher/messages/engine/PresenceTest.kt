package dev.omalauncher.messages.engine

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PresenceTest {
    @Test fun missingStopExpiresAndOtherChatsStaySeparate() {
        val state = TypingState().event("a", "ana", "Ana", true, 1000).event("b", "ben", "Ben", true, 2000)
        assertEquals(listOf("Ana"), state.names("a", 3000))
        assertTrue(state.names("a", 9000).isEmpty())
        assertEquals(listOf("Ben"), state.expire(9000).names("b", 9000))
        assertTrue(state.event("b", "ben", "Ben", false, 3000).names("b", 3000).isEmpty())
    }
    @Test fun repeatedKeystrokesRefreshButDoNotFloodAndStopIsIdempotent() {
        val p = TypingPulse()
        assertEquals(true, p.changed("h", 1000))
        assertNull(p.changed("hi", 1100))
        assertEquals(true, p.changed("hello", 4000))
        assertEquals(false, p.changed("", 4100))
        assertNull(p.stop())
    }
    @Test fun receiptsSurviveOlderEventsAndRestartAndAcceptanceIsNotDelivery() {
        val pending = ConversationBook().fromMac("c", "Ana", false, emptyList()).waiting("c", "x", "hello", 1000)
        assertEquals(Delivery.Sending, pending["c"]!!.messages.single().delivery)
        val accepted = pending.accepted("c", "x")
        assertEquals(Delivery.Sent, accepted["c"]!!.messages.single().delivery)
        val read = MacWire.toMessages(MacWire.msg(JSONObject("""{"id":"s","chatId":"c","fromMe":true,"text":"hello","at":1000,"read":true,"clientId":"x"}""")))
        val book = accepted.fromMac("c", null, null, read)
        val older = read.single().copy(delivery = Delivery.Delivered)
        val restored = ConversationBook.fromJson(book.toJson()).fromMac("c", null, null, listOf(older), counting = false)
        assertEquals(Delivery.Read, restored["c"]!!.messages.single().delivery)
        assertEquals("c:x", restored["c"]!!.messages.single().renderKey)
    }
    @Test fun richMetadataRejectsExecutableUrlsAndKeepsOnlyAttachmentIds() {
        assertNull(RichLink.read(JSONObject("""{"url":"javascript:alert(1)","title":"oops"}""")))
        val link = RichLink.read(JSONObject("""{"url":"https://example.com/story","title":"A title","imageAttachmentId":"preview-1","width":999999}"""))!!
        assertEquals(8192, link.width)
        assertEquals(link, RichLink.read(link.json()))
    }
}
