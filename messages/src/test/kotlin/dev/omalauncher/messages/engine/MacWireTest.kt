package dev.omalauncher.messages.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shapes are the service's /v1 contract (soma-imessage-service README, "API (v1): the phone's contract"). */
class MacWireTest {
    private val chats =
        """{"chats":[
          {"id":"iMessage;+;chat0001","name":"Weekend","participants":["+15555550111","ben@example.com"],"isGroup":true,"unread":2,"updatedAt":5000,
           "lastMessage":{"id":"M4","chatId":"iMessage;+;chat0001","fromMe":false,"sender":"ben@example.com","text":"Look at this","at":5000,
             "attachments":[{"id":"A1","name":"sunset.png","mime":"image/png","size":120}],"replyTo":null,"tapbacks":[],"edited":false,"unsent":false,"service":"iMessage"}},
          {"id":"iMessage;-;+15555550111","name":null,"participants":["+15555550111"],"isGroup":false,"unread":0,"updatedAt":4000,"lastMessage":null}
        ]}"""

    @Test
    fun readsChats() {
        val list = MacWire.chats(chats)
        assertEquals(2, list.size)
        assertEquals("Weekend", list[0].name)
        assertTrue(list[0].isGroup)
        assertEquals(2, list[0].unread)
        assertEquals(listOf("+15555550111", "ben@example.com"), list[0].participants)
        assertEquals("Look at this", list[0].last!!.text)
        // A 1:1 chat has no name of its own: the phone names it from contacts.
        assertNull(list[1].name)
        assertNull(list[1].last)
    }

    @Test
    fun attachmentsComeBeforeTheWordsWithNames() {
        val m = MacWire.chats(chats)[0].last!!
        val kept = MacWire.toMessages(m, nameOf = { if (it == "ben@example.com") "Ben" else null })
        assertEquals(listOf("1 Photo", "Look at this"), kept.map { it.text })
        assertEquals(listOf("M4#a", "M4"), kept.map { it.id })
        assertTrue(kept.all { it.sender == "Ben" })
        // The app's own reading of the words turns the summary into an attachment bubble.
        assertTrue(Parts.of(kept[0].text).single() is Part.Attachment)
    }

    @Test
    fun aThreadedReplyCarriesItsQuoteAndTheEchoItsClientId() {
        val m =
            MacWire.msg(
                JSONObject("""{"id":"M3","chatId":"c","fromMe":true,"sender":null,"text":"Perfect","at":3,"replyTo":"M2","clientId":"k1","attachments":[]}"""),
            )
        assertEquals("k1", m.clientId)
        val kept = MacWire.toMessages(m, quoteOf = { if (it == "M2") "Ana: dinner at 7?" else null }).single()
        assertEquals("Ana: dinner at 7?", kept.quote)
        assertTrue(kept.fromMe)
        // No sender and not the owner's: still someone.
        // The Mac's name for the sender when the phone has none; the phone's own contacts first.
        val named = MacWire.msg(JSONObject("""{"id":"n","chatId":"c","fromMe":false,"sender":"+1555","senderName":"Ana Ruiz","text":"hi","at":1}"""))
        assertEquals("Ana Ruiz", MacWire.toMessages(named).single().sender)
        assertEquals("Ana", MacWire.toMessages(named, nameOf = { "Ana" }).single().sender)
        assertEquals("Unknown", MacWire.toMessages(MacWire.msg(JSONObject("""{"id":"x","chatId":"c","fromMe":false,"text":"hi","at":1}"""))).single().sender)
    }

    @Test
    fun unsentAndGroupChangesShowNothing() {
        assertTrue(MacWire.toMessages(MacWire.msg(JSONObject("""{"id":"u","chatId":"c","fromMe":true,"text":"oops","at":1,"unsent":true}"""))).isEmpty())
        assertTrue(MacWire.toMessages(MacWire.msg(JSONObject("""{"id":"s","chatId":"c","fromMe":false,"text":"Ana named the chat","at":1,"system":true}"""))).isEmpty())
    }

    @Test
    fun kindsAndPlaceholders() {
        val m =
            MacWire.msg(
                JSONObject(
                    """{"id":"m","chatId":"t","at":1,"fromMe":true,"text":"￼￼￼","attachments":[
                    {"id":"a","name":"IMG_1.HEIC","mime":"image/heic"},{"id":"b","name":"clip.mov","mime":"video/quicktime"},{"id":"c","name":"x.gif","mime":"image/gif"}]}""",
                ),
            )
        assertEquals(listOf("1 Photo & 1 Video & 1 GIF"), MacWire.toMessages(m).map { it.text })
        assertNull(MacWire.summary(emptyList()))
    }

    @Test
    fun aPagesBoundaryRepeatsOnlyOnce() {
        val page = """{"messages":[{"id":"a","chatId":"c","at":2,"fromMe":true,"text":"two"},{"id":"b","chatId":"c","at":1,"fromMe":true,"text":"one"},{"id":"a","chatId":"c","at":2,"fromMe":true,"text":"two"}]}"""
        assertEquals(listOf("b", "a"), MacWire.messages(page).map { it.id })
    }

    @Test
    fun streamReadsEventsAndRemembersWhereItWas() {
        val s = MacWire.Stream()
        val lines = listOf(": ping", "id: 3302689d:41", "event: message.new", "data: {\"a\":1,", "data: \"b\":2}", "", "", "id: 3302689d:41", "event: ready", "")
        val events = lines.mapNotNull { s.line(it) }
        assertEquals(2, events.size)
        assertEquals(MacWire.Event("3302689d:41", "message.new", "{\"a\":1,\n\"b\":2}"), events[0])
        // A control event with no data still arrives.
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
