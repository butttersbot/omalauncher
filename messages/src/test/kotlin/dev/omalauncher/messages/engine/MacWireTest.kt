package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shapes are the service's demo data (soma-imessage-service DemoBackend), which follow platform-imessage's. */
class MacWireTest {
    private val threads =
        """{"items":[
          {"id":"iMessage;+;chat000000000000000001","title":"Weekend","type":"group","isUnread":true,"folderName":"normal",
           "participants":{"items":[{"id":"+15555550111","phoneNumber":"+15555550111","fullName":"Ana Ruiz"},{"id":"ben@example.com","email":"ben@example.com"}],"hasMore":false},
           "messages":{"items":[{"id":"DEMO-4","threadID":"iMessage;+;chat000000000000000001","timestamp":5000,"senderID":"ben@example.com","isSender":false,
             "attachments":[{"id":"DEMO-ATT-1","type":"img","mimeType":"image/png","fileName":"sunset.png","srcURL":"/v1/files/DEMO-ATT-1"}],"text":"Look at this"}],"hasMore":false},
           "timestamp":5000},
          {"id":"iMessage;-;+15555550111","type":"single","isUnread":false,
           "participants":{"items":[{"id":"+15555550111","phoneNumber":"+15555550111"}],"hasMore":false},
           "messages":{"items":[],"hasMore":true}}
        ],"hasMore":false}"""

    @Test
    fun readsThreads() {
        val list = MacWire.chats(threads)
        assertEquals(2, list.size)
        val group = list[0]
        assertEquals("Weekend", group.name)
        assertTrue(group.isGroup)
        assertTrue(group.unread)
        assertEquals(mapOf("+15555550111" to "Ana Ruiz", "ben@example.com" to "ben@example.com"), group.people.associate { it.handle to it.name })
        assertEquals("Look at this", group.last!!.text)
        assertEquals("/v1/files/DEMO-ATT-1", group.last!!.attachments.single().src)
        // No title: the people in it.
        assertEquals("+15555550111", list[1].name)
        assertNull(list[1].last)
    }

    @Test
    fun attachmentsComeBeforeTheWordsWithNames() {
        val m = MacWire.chats(threads)[0].last!!
        val kept = MacWire.toMessages(m, nameOf = { if (it == "ben@example.com") "Ben" else null })
        assertEquals(listOf("1 Photo", "Look at this"), kept.map { it.text })
        assertEquals(listOf("DEMO-4#a", "DEMO-4"), kept.map { it.id })
        assertTrue(kept.all { it.sender == "Ben" })
        // The app's own reading of the words turns the summary into an attachment bubble.
        assertTrue(Parts.of(kept[0].text).single() is Part.Attachment)
    }

    @Test
    fun aThreadedReplyCarriesItsQuote() {
        val m =
            MacWire.msg(
                JSONObject("""{"id":"DEMO-3","threadID":"t","timestamp":3,"senderID":"+1555","isSender":false,"text":"Perfect, see you there","linkedMessageID":"DEMO-2"}"""),
            )
        val kept = MacWire.toMessages(m, quoteOf = { if (it == "DEMO-2") "You: Yes! 7pm" else null }).single()
        assertEquals("You: Yes! 7pm", kept.quote)
        assertEquals("+1555", kept.sender)
    }

    @Test
    fun kindsAndMarks() {
        val m =
            MacWire.msg(
                JSONObject(
                    """{"id":"m","threadID":"t","timestamp":1,"isSender":true,"text":"￼￼","attachments":[
                    {"id":"a","fileName":"IMG_1.HEIC","type":"img"},{"id":"b","fileName":"clip.mov"},{"id":"c","fileName":"x.gif","type":"img"}]}""",
                ),
            )
        // Only the attachments: the placeholders in the words go.
        assertEquals(listOf("1 Photo & 1 Video & 1 GIF"), MacWire.toMessages(m).map { it.text })
        assertTrue(MacWire.toMessages(m).single().fromMe)
        assertNull(MacWire.summary(emptyList()))
    }

    @Test
    fun readsSendsAndSyncEvents() {
        assertEquals(listOf("S1"), MacWire.sent("""[{"id":"S1","threadID":"t","timestamp":1,"isSender":true,"text":"hi"}]""").map { it.id })
        assertTrue(MacWire.sent("""{"result":true}""").isEmpty())
        val s = MacWire.sync("""{"type":"state_sync","objectName":"message","mutationType":"delete","objectIDs":{"threadID":"t1"},"entries":["m9"]}""")!!
        assertEquals("message", s.objectName)
        assertEquals("delete", s.mutation)
        assertEquals("t1", s.chatId)
        assertEquals("m9", s.entries.getString(0))
        assertNull(MacWire.sync("""{"type":"user_activity","threadID":"t1"}"""))
    }

    @Test
    fun streamReadsEventsAndRemembersWhereItWas() {
        val s = MacWire.Stream()
        val lines = listOf(": ping", "id: 3302689d:41", "event: imessage", "data: {\"a\":1,", "data: \"b\":2}", "", "", "event: ready", "data: {}", "")
        val events = lines.mapNotNull { s.line(it) }
        assertEquals(2, events.size)
        assertEquals(MacWire.Event("3302689d:41", "imessage", "{\"a\":1,\n\"b\":2}"), events[0])
        assertEquals("ready", events[1].type)
        assertEquals("3302689d:41", s.lastId)
        // A broken stream drops the half-read event but keeps the place.
        s.line("id: 3302689d:42")
        s.line("data: half")
        s.reset()
        assertEquals("3302689d:41", s.lastId)
        assertEquals(MacWire.Event(null, "message", "x"), listOf("data: x", "").mapNotNull { s.line(it) }.single())
    }

    @Test
    fun onlyTailnetOrHttps() {
        assertEquals("100.120.219.46", MacAddress.of("http://100.120.219.46:8765/", "t")!!.host)
        assertEquals("http://100.120.219.46:8765", MacAddress.of("http://100.120.219.46:8765/", "t")!!.base)
        assertTrue(MacAddress.of("http://mac.tail1234.ts.net:8765", "t") != null)
        assertTrue(MacAddress.of("https://example.com", "t") != null)
        assertNull(MacAddress.of("http://192.168.1.5:8765", "t"))
        assertNull(MacAddress.of("http://100.200.1.1:8765", "t"))
        assertNull(MacAddress.of("http://100.120.219.46:8765", ""))
        assertNull(MacAddress.of("ftp://100.120.219.46", "t"))
        val at = MacAddress("http://100.120.219.46:8765", "secret")
        assertEquals(at, MacAddress.fromJson(at.toJson()))
    }
}
