package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageChangesTest {
    @Test fun serverRemainingTimeUsesReceiptTimeAndCachedPermissionsNeverAuthorizeAnEdit() {
        val allowed = AllowedChanges.read(JSONObject("""{"edit":true,"unsend":true,"asOf":1000,"editUntil":1200,"unsendUntil":1030}"""), now = 9000)
        assertTrue(allowed.canEdit(9199)); assertFalse(allowed.canEdit(9200))
        assertTrue(allowed.canUnsend(9029)); assertFalse(allowed.canUnsend(9030))
        assertFalse(allowed.canEdit(8999))
        val m = Message("new", 1, "", id = "m", edited = true, editedAt = 2, editCount = 1,
            editHistory = listOf(MessageRevision("old", 1, true)), editHistoryComplete = true, changes = allowed)
        val kept = ConversationBook.fromJson(ConversationBook().fromMac("c", "Chat", false, listOf(m)).toJson())["c"]!!.messages.single()
        assertEquals(m.editHistory, kept.editHistory)
        assertEquals(m.editCount, kept.editCount)
        assertFalse(kept.changes.canEdit(9001))
    }

    @Test fun httpAcceptanceAloneNeverConfirmsAnEditOrUnsend() {
        val pending = PendingMessageChange("m", "new", accepted = true)
        assertFalse(pending.confirmedBy(Message("old", 1, "", id = "m")))
        assertFalse(pending.confirmedBy(Message("new", 1, "", id = "m")))
        assertTrue(pending.confirmedBy(Message("new", 1, "", id = "m", edited = true)))
        val undo = PendingMessageChange("m", null, accepted = true)
        assertFalse(undo.confirmedBy(Message("", 1, "", id = "m")))
        assertTrue(undo.confirmedBy(Message("You unsent a message", 1, "", id = "m", unsent = true)))
    }

    @Test fun unsentMediaAndTextBecomeOneStableNoticeAndCannotBeResurrectedByStaleHistory() {
        val photo = Message("1 Photo", 1, "", id = "root#a", files = listOf("PRIVATE-MEDIA|image/png"))
        val body = Message("PRIVATE-ORIGINAL", 1, "", id = "root")
        val reply = Message("reply", 2, "Ana", id = "reply", replyTo = "root", quote = "You: PRIVATE-ORIGINAL")
        val before = ConversationBook().fromMac("c", "Chat", false, listOf(photo, body, reply))
        val notice = Message("You unsent a message", 1, "", id = "root", unsent = true)
        val after = before.fromMac("c", null, null, listOf(notice), counting = false)
            .fromMac("c", null, null, listOf(photo, body), counting = false)
        val messages = after["c"]!!.messages
        assertEquals(2, messages.size)
        assertTrue(messages.first().unsent)
        assertEquals(photo.renderKey, messages.first().renderKey)
        assertEquals("Message was unsent", messages.last().quote)
        assertFalse(after.toJson().contains("PRIVATE-ORIGINAL"))
        assertFalse(after.toJson().contains("PRIVATE-MEDIA"))
        assertTrue(ConversationBook.fromJson(after.toJson())["c"]!!.messages.first().unsent)
    }

    @Test fun staleBodyCannotUndoAnEditButReadReceiptStillAdvancesAndQuotesUpdate() {
        val old = Message("old", 1, "", id = "root", delivery = Delivery.Delivered)
        val reply = Message("reply", 2, "Ana", id = "reply", replyTo = "root")
        val edited = old.copy(text = "new", edited = true, editedAt = 10, editCount = 1)
        val book = ConversationBook().fromMac("c", "Chat", false, listOf(old, reply))
            .fromMac("c", null, null, listOf(edited), counting = false)
            .fromMac("c", null, null, listOf(old.copy(delivery = Delivery.Read)), counting = false)
        assertEquals("new", book["c"]!!.messages.first().text)
        assertEquals(Delivery.Read, book["c"]!!.messages.first().delivery)
        assertEquals("You: new", book["c"]!!.messages.last().quote)
    }
}
