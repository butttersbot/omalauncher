package dev.omalauncher.herd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The phone's side of the herd reporters (~/ClaudeSync/projects/herd): which one it asks, and what the sheep's page may ask of it. */
class HerdTest {
    private val hosts = listOf("mac", "laptop", "mg1")

    @Test
    fun asksInOrderUntilOneAnswers() {
        val asked = mutableListOf<String>()
        val found = firstAnswer(hosts, null) { asked += it; if (it == "laptop") "ok" else null }
        assertEquals("laptop" to "ok", found)
        assertEquals(listOf("mac", "laptop"), asked)
    }

    @Test
    fun startsWithTheOneThatAnsweredLast() {
        val asked = mutableListOf<String>()
        firstAnswer(hosts, "mg1") { asked += it; "ok" }
        assertEquals(listOf("mg1"), asked)
    }

    @Test
    fun aHostThatIsDownIsPassedOver() {
        val found = firstAnswer(hosts, "mac") { if (it == "mac") throw IOException("refused") else "ok" }
        assertEquals("laptop" to "ok", found)
    }

    @Test
    fun nothingWhenNoneAnswers() {
        assertNull(firstAnswer(hosts, null) { null })
    }

    @Test
    fun aRememberedHostThatLeftTheListIsIgnored() {
        val asked = mutableListOf<String>()
        firstAnswer(hosts, "gone") { asked += it; null }
        assertEquals(hosts, asked)
    }

    @Test
    fun readsThePagesCommands() {
        assertEquals(PetCommand.Expand, petCommand("""{"cmd": "expand"}"""))
        assertEquals(PetCommand.Collapse, petCommand("""{"cmd": "collapse"}"""))
        assertEquals(PetCommand.Show("add7d44a-962b"), petCommand("""{"cmd": "show", "id": "add7d44a-962b"}"""))
        assertEquals(PetCommand.Copy("claude --resume x"), petCommand("""{"cmd": "copy", "text": "claude --resume x"}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "send", "device": "mg1"}"""))
        assertEquals(PetCommand.Unknown, petCommand("not json"))
        assertEquals(PetCommand.Stroll(-120f, 38f), petCommand("""{"cmd": "stroll", "dx": -120, "speed": 38}"""))
        assertEquals(PetCommand.Stroll(5000f.coerceAtMost(2000f), 40f), petCommand("""{"cmd": "stroll", "dx": 5000}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "stroll"}"""))
        assertEquals(PetCommand.Room(280f, 260f), petCommand("""{"cmd": "room", "w": 280, "h": 260}"""))
        assertEquals(PetCommand.Room(null, null), petCommand("""{"cmd": "room"}"""))
    }

    @Test
    fun readsThePocketsCommands() {
        assertEquals(PetCommand.PocketGet("a1b2", "cat.jpg", PocketAction.Save), petCommand("""{"cmd": "pocketget", "id": "a1b2", "name": "cat.jpg", "action": "save"}"""))
        assertEquals(PetCommand.PocketGet("a1b2", "cat.jpg", PocketAction.Open), petCommand("""{"cmd": "pocketget", "id": "a1b2", "name": "../../cat.jpg", "action": "open"}"""))
        assertEquals(PetCommand.PocketPaste, petCommand("""{"cmd": "pocketpaste"}"""))
        assertEquals("an id never carries a path", PetCommand.Unknown, petCommand("""{"cmd": "pocketget", "id": "../v1", "name": "x", "action": "open"}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "pocketget", "id": "a1b2", "name": "x", "action": "delete"}"""))
    }

    @Test
    fun readsTheTailshareTabsCommands() {
        assertEquals(PetCommand.SendClip("macbook-pro"), petCommand("""{"cmd": "sendclip", "device": "macbook-pro"}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "sendclip", "device": "x/../y"}"""))
        assertEquals(PetCommand.InboxGet("clip-1.png", "copy"), petCommand("""{"cmd": "inboxget", "name": "clip-1.png", "action": "copy"}"""))
        assertEquals("a name with a path is no item", PetCommand.Unknown, petCommand("""{"cmd": "inboxget", "name": "../index.json", "action": "remove"}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "inboxget", "name": "a.txt", "action": "share"}"""))
    }

    @Test
    fun aClipIsNamedForTheReceiver() {
        val at = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse("20261016-061500")!!
        assertEquals("clip-20261016-061500-from-galaxy-z-fold8.txt", clipName(at, picture = false))
        assertEquals("clip-20261016-061500-from-galaxy-z-fold8.png", clipName(at, picture = true))
    }

    @Test
    fun readsWhatTailshareReceived() {
        val item = inboxItem("""{"name": "clip-1.txt", "from": "macbook-pro", "t": 1792150000.5, "kind": "text", "size": 5, "text": "hello"}""")!!
        assertEquals(InboxItem("clip-1.txt", "macbook-pro", 1792150000.5, "text", 5, "hello", null), item)
        assertEquals("a null sender is no name", null, inboxItem("""{"name": "a.png", "from": null, "kind": "image", "size": 9}""")!!.from)
        assertEquals("an unknown kind is a file", "file", inboxItem("""{"name": "a.bin", "kind": "exe"}""")!!.kind)
        assertNull(inboxItem("""{"kind": "text"}"""))
        val json = org.json.JSONArray(inboxJson(listOf(item)) { "content://x/clip-1.txt" }).getJSONObject(0)
        assertEquals("hello", json.getString("text"))
        assertEquals("content://x/clip-1.txt", json.getString("src"))
    }

    @Test
    fun aPocketNameIsOneSafeFileName() {
        assertEquals("cat.jpg", pocketName("/sdcard/DCIM/cat.jpg"))
        assertEquals("evil.sh", pocketName("..\\..\\.evil.sh"))
        assertEquals("a_b.txt", pocketName("a\nb.txt"))
        assertEquals("pocket-item", pocketName("..."))
        assertEquals("x9", pocketName("", "x9"))
        assertEquals("""{"name":"a.txt","state":"failed","error":"no"}""", pocketBusyJson("a.txt", "failed", "no"))
    }

    @Test
    fun theBubblesRoomLeavesTheSheepWhereItStands() {
        // Mid-screen: grows up and leftwards; the sheep's square stays at 500,1000.
        assertEquals(SheepWindow(370, 870, 280, 280, "right"), sheepWindow(500, 1000, 150, 280, 280, 1200, 1800))
        // At the left edge: grows rightwards instead.
        assertEquals(SheepWindow(20, 870, 280, 280, "left"), sheepWindow(20, 1000, 150, 280, 280, 1200, 1800))
        // Back to just the sheep.
        assertEquals(SheepWindow(500, 1000, 150, 150, "right"), sheepWindow(500, 1000, 150, 0, 0, 1200, 1800))
        // Near the top a message's window is cut to the room above, so the sheep stays at the window's bottom and still
        // follows a drag (Boss, 2026-10-19); it used to be pinned to the top, leaving the sheep stuck on one row.
        for (sy in listOf(0, 40, 100)) {
            val win = sheepWindow(500, sy, 150, 280, 400, 1200, 1800)
            assertEquals(sy + 150, win.y + win.h)
            assertEquals(0, win.y)
        }
        assertEquals(SheepWindow(370, 0, 280, 400, "right"), sheepWindow(500, 250, 150, 280, 400, 1200, 1800))
    }

    @Test
    fun aSessionIdNeverCarriesAPath() {
        assertEquals("t9f45b7c", safeSessionId("t9f45b7c"))
        assertNull(safeSessionId("../v1/status"))
        assertNull(safeSessionId("a?b=c"))
        assertNull(safeSessionId("ab"))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd": "show", "id": "../../etc"}"""))
    }

    @Test
    fun onlyAJsonObjectReachesThePage() {
        assertEquals(JSONObject("""{"machines": []}""").toString(), herdJson("""{"machines": []}"""))
        assertNull(herdJson("1); alert(1"))
        assertNull(herdJson(null))
    }

    @Test
    fun unreachableSaysWhy() {
        val o = JSONObject(herdUnreachable())
        assertEquals(0, o.getJSONArray("machines").length())
        assert(o.getString("error").contains("Tailscale"))
    }

    @Test
    fun asksThePortTheReportersListenOn() {
        assertEquals("http://jacks-mac-pro:8479/v1/herd", herdUrl("jacks-mac-pro", "/v1/herd"))
    }

    /**
     * Boss, 2026-10-17: with the menu open the sheep was cut off at the bottom edge. The open window keeps to one zone a
     * side, the bottom corner, above the gesture bar and below the status bar, wherever the sheep stood (Fold cover).
     */
    @Test fun theOpenPanelKeepsToABottomCornerClearOfTheBars() {
        // The sheep stood low on the right: the window ends above the gesture bar, so the sheep in its corner is whole.
        val right = sheepOpenWindow("right", 1040, 1577, 1248, 1972, top = 110, bottom = 63, margin = 21)
        assertEquals(SheepWindow(208, 1972 - 63 - 21 - 1577, 1040, 1577, "right"), right)
        assertTrue(right.y + right.h <= 1972 - 63)
        // On the left side it is the bottom-left corner.
        assertEquals(0, sheepOpenWindow("left", 1040, 1577, 1248, 1972, 110, 63, 21).x)
        // A short screen (folded flat, landscape): the panel shrinks to the room between the bars, never under them.
        val short = sheepOpenWindow("right", 1040, 1577, 2200, 1000, top = 80, bottom = 60, margin = 20)
        assertEquals(1000 - 80 - 60 - 40, short.h)
        assertEquals(80 + 20, short.y)
        assertTrue(short.y + short.h <= 1000 - 60)
        // A screen narrower than the panel: as wide as the screen.
        assertEquals(SheepWindow(0, 1972 - 63 - 21 - 1577, 900, 1577, "right"), sheepOpenWindow("right", 1040, 1577, 900, 1972, 110, 63, 21))
    }
}
