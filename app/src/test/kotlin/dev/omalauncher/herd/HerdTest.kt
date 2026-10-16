package dev.omalauncher.herd

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
