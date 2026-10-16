package dev.omalauncher.assist

import dev.omalauncher.widgets.AgentTile
import dev.omalauncher.widgets.tileWhere
import org.junit.Assert.*
import org.junit.Test

/**
 * The Mac Pro and the MacBook Pro side by side (MacMachines.kt): the front agent's board, the status file `oma
 * status` prints, the tabs and the tiles each say which Mac a thread is on, and one Mac out of reach hides nothing
 * of the other. With one Mac every one of them reads as it did before there were two.
 */
class TwoMacsTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun thread(id: String, name: String, status: MacThreadStatus = MacThreadStatus.Idle) =
        MacThread(id, name, "/Users/boss/Projects/${name.lowercase()}", ThreadHome.Mac, "claude", "", status, now / 1000 - 300, "done.", 0, 3, emptyList())

    private val pro = MacSeen(MAC_FIRST, "Mac Pro", reachable = true, seen = now, listed = true)
    private val book = MacSeen("macbook-pro", "MacBook Pro", reachable = false, seen = now - 5 * minute, listed = true)
    private val threads = listOf(thread("t1a2b3c4d", "FF4"), thread("macbook-pro/t5e6f7a8b", "Herd", MacThreadStatus.Working))
    private val view = macWith(MacView(threads = threads), listOf(pro, book))

    @Test fun theBoardSaysHowEachMacStandsAndWhichMacEachThreadIsOn() {
        val text = boardText(BuildBoard(working = false), macBoard(threads, view.reachable, view.seen, now, macs = view.macs))
        assertTrue(text, text.contains("Macs: Mac Pro: reachable; MacBook Pro: not reachable, last seen 5 min ago."))
        assertTrue(text, text.contains("\"FF4\" id t1a2b3c4d on the Mac Pro in folder ff4"))
        // The MacBook's thread stays on the board while its Mac is out of reach, under the id its mark names.
        assertTrue(text, text.contains("\"Herd\" id macbook-pro/t5e6f7a8b on the MacBook Pro in folder herd: working"))
        // A mark naming it reads whole, slash and all, and routes to that very thread.
        val read = frontRead("<<${Route.mac("macbook-pro/t5e6f7a8b")}>>", ended = true) as FrontRead.HandOver
        assertEquals(Route.mac("macbook-pro/t5e6f7a8b"), read.to)
        val routed = frontRoute(read.to, macBoard(threads, view.reachable, view.seen, now, macs = view.macs)) as FrontRoute.Mac
        assertEquals("macbook-pro/t5e6f7a8b", routed.thread.id)
    }

    @Test fun withOneMacTheBoardReadsAsItAlwaysDid() {
        val one = listOf(thread("t1a2b3c4d", "FF4"))
        val before = boardText(BuildBoard(working = false), macBoard(one, true, now, now))
        val after = boardText(BuildBoard(working = false), macBoard(one, true, now, now, macs = listOf(pro)))
        assertEquals(before, after)
        assertTrue(after, after.contains("Mac: reachable."))
        assertFalse(after, after.contains("Mac Pro"))
    }

    @Test fun theStatusFileNamesEachMacAndEachThreadsMac() {
        val status = agentsStatus(BuilderFacts(now, working = false), view, "x").getJSONObject("mac")
        val macs = status.getJSONArray("macs")
        assertEquals(2, macs.length())
        assertEquals("MacBook Pro", macs.getJSONObject(1).getString("name"))
        assertFalse(macs.getJSONObject(1).getBoolean("reachable"))
        assertEquals(5, macs.getJSONObject(1).getInt("lastSeenMinAgo"))
        val listed = status.getJSONArray("threads")
        assertEquals("Mac Pro", listed.getJSONObject(0).getString("mac"))
        assertEquals("macbook-pro/t5e6f7a8b", listed.getJSONObject(1).getString("id"))
        assertEquals("MacBook Pro", listed.getJSONObject(1).getString("mac"))
        // One Mac: no "macs" and no "mac" on its threads, as before.
        val one = agentsStatus(BuilderFacts(now, working = false), macWith(MacView(threads = threads.take(1)), listOf(pro)), "x").getJSONObject("mac")
        assertFalse(one.has("macs"))
        assertFalse(one.getJSONArray("threads").getJSONObject(0).has("mac"))
    }

    @Test fun eachThreadIsAsReachableAsItsOwnMacAndNamesIt() {
        val shown = agentThreads(emptyList(), BuilderNow(), view).filter { it.mac }
        val ff4 = shown.first { it.name == "FF4" }
        val herd = shown.first { it.name == "Herd" }
        assertTrue(ff4.reachable)
        assertEquals("Mac Pro", ff4.machine)
        assertFalse(herd.reachable)
        assertEquals("MacBook Pro", herd.machine)
        assertEquals(macThreadKey("macbook-pro/t5e6f7a8b"), herd.key)
        assertEquals("macbook-pro/t5e6f7a8b", macThreadId(herd.key))
        // The tiles: the Mac's own name, and out of reach only for the one that is.
        assertEquals("MACBOOK PRO · OUT OF REACH", tileWhere(AgentTile(herd.name, herd), now))
        assertTrue(tileWhere(AgentTile(ff4.name, ff4), now).startsWith("MAC PRO · "))
        // One Mac: no name on the tab, "MAC" on the tile.
        val alone = agentThreads(emptyList(), BuilderNow(), macWith(MacView(threads = threads.take(1)), listOf(pro))).first { it.mac }
        assertEquals("", alone.machine)
        assertTrue(tileWhere(AgentTile(alone.name, alone), now).startsWith("MAC · "))
    }
}
