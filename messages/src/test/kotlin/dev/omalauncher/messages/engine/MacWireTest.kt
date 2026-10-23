package dev.omalauncher.messages.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacWireTest {
    private val chats =
        """{"chats":[
          {"id":"iMessage;-;+15551234567","name":"Mina","participants":["+15551234567"],"isGroup":false,"unread":2,"updatedAt":5,
           "lastMessage":{"id":"m9","chatId":"iMessage;-;+15551234567","fromMe":false,"sender":"+15551234567","senderName":"Mina","text":"see you","at":5000,"attachments":[]}},
          {"id":"chat77","name":null,"participants":["a@x.com","b@y.com"],"isGroup":true,"unread":0,"updatedAt":4,"lastMessage":null}
        ]}"""

    @Test
    fun readsChats() {
        val list = MacWire.chats(chats)
        assertEquals(2, list.size)
        assertEquals("Mina", list[0].name)
        assertEquals(2, list[0].unread)
        assertEquals("see you", list[0].last!!.text)
        // No name: the people in it.
        assertEquals("a@x.com, b@y.com", list[1].name)
        assertTrue(list[1].isGroup)
        assertNull(list[1].last)
    }

    @Test
    fun attachmentsComeBeforeTheWords() {
        val m =
            MacWire.msg(
                org.json.JSONObject(
                    """{"id":"m1","chatId":"c","fromMe":true,"sender":null,"text":"look","at":7,"clientId":"k1",
                    "attachments":[{"id":"a","name":"1.heic","mime":"image/heic"},{"id":"b","name":"2.jpg","mime":"image/jpeg"},{"id":"v","name":"v.mov","mime":"video/quicktime"}]}""",
                ),
            )
        assertEquals("k1", m.clientId)
        val kept = MacWire.toMessages(m)
        assertEquals(listOf("2 Photos & 1 Video", "look"), kept.map { it.text })
        assertEquals(listOf("m1#a", "m1"), kept.map { it.id })
        assertTrue(kept.all { it.fromMe })
        // The app's own reading of the words turns the summary into an attachment bubble.
        assertTrue(Parts.of(kept[0].text).single() is Part.Attachment)
    }

    @Test
    fun senderFallsBackToTheHandle() {
        val m = MacWire.msg(org.json.JSONObject("""{"id":"m","chatId":"c","fromMe":false,"sender":"+1555","text":"hi","at":1}"""))
        assertEquals("+1555", MacWire.toMessages(m).single().sender)
        assertNull(MacWire.summary(emptyList()))
    }

    @Test
    fun streamReadsEventsAndRemembersWhereItWas() {
        val s = MacWire.Stream()
        val lines = listOf(": ping", "id: 41", "event: message.new", "data: {\"a\":1,", "data: \"b\":2}", "", "", "event: typing", "data: {}", "")
        val events = lines.mapNotNull { s.line(it) }
        assertEquals(2, events.size)
        assertEquals(MacWire.Event("41", "message.new", "{\"a\":1,\n\"b\":2}"), events[0])
        assertEquals("typing", events[1].type)
        assertEquals("41", s.lastId)
        // A broken stream drops the half-read event but keeps the place.
        s.line("id: 42")
        s.line("data: half")
        s.reset()
        assertEquals("41", s.lastId)
        assertEquals(MacWire.Event(null, "message", "x"), listOf("data: x", "").mapNotNull { s.line(it) }.single())
    }

    @Test
    fun onlyTailnetOrHttps() {
        assertEquals("100.120.219.46", MacAddress.of("http://100.120.219.46:8490/", "t")!!.host)
        assertEquals("http://100.120.219.46:8490", MacAddress.of("http://100.120.219.46:8490/", "t")!!.base)
        assertTrue(MacAddress.of("http://mac.tail1234.ts.net:8490", "t") != null)
        assertTrue(MacAddress.of("https://example.com", "t") != null)
        assertNull(MacAddress.of("http://192.168.1.5:8490", "t"))
        assertNull(MacAddress.of("http://100.200.1.1:8490", "t"))
        assertNull(MacAddress.of("http://100.120.219.46:8490", ""))
        assertNull(MacAddress.of("ftp://100.120.219.46", "t"))
        val at = MacAddress("http://100.120.219.46:8490", "secret")
        assertEquals(at, MacAddress.fromJson(at.toJson()))
    }
}
