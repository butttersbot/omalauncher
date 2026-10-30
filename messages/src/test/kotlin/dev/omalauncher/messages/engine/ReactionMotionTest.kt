package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReactionMotionTest {
    @Test fun attributionSurvivesPartsEchoAndRestart() {
        val raw = JSONObject("""{"id":"m","chatId":"c","text":"hello","at":1,"tapbacks":[{"sender":"ana","kind":"love","fromMe":false},{"sender":"me","kind":"like","fromMe":true}]}""")
        val m = MacWire.toMessages(MacWire.msg(raw), nameOf = { if (it == "ana") "Ana" else null }).single()
        val book = ConversationBook().fromMac("c", "Chat", false, listOf(m))
        val restored = ConversationBook.fromJson(book.toJson())["c"]!!.messages.single()
        assertEquals(listOf("Ana", "You"), restored.reactions.map { it.name })
        assertEquals(listOf("love", "me:like"), restored.tapbacks)
    }
    @Test fun entranceRunsOnceAndNeverAfterRestoreOrDelayedHistory() {
        SendMotion.mark("fresh", 1000)
        assertTrue(SendMotion.take("c:fresh", 1100))
        assertFalse(SendMotion.take("c:fresh", 1200)) // Same stable row's echo/recomposition.
        assertFalse(SendMotion.take("server-id", 1200)) // History never registers an entrance.
        SendMotion.mark("old", 1000)
        assertFalse(SendMotion.take("c:old", 7000))
    }
    @Test fun customRoomTitleNeverBecomesAReactorName() {
        val room = MacWire.chat(JSONObject("""{"id":"c","name":"Link previews","participants":["ana"],"participantNames":{"ana":"Ana Demo"}}"""))
        assertEquals("Ana Demo", MacWire.participantNames(listOf(room))["ana"])
        val oldServer = MacWire.chat(JSONObject("""{"id":"old","name":"Project alpha","participants":["unknown"]}"""))
        assertFalse(MacWire.participantNames(listOf(oldServer)).containsKey("unknown"))
    }
}
