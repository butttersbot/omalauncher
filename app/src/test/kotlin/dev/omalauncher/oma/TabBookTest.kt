package dev.omalauncher.oma

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.BuilderId
import dev.omalauncher.assist.BuilderNow
import dev.omalauncher.assist.MacView
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.agentThreads
import dev.omalauncher.widgets.AGENT_TILE_IDLE_HOURS
import dev.omalauncher.widgets.liveThreads
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** What he makes of the chat's tabs (Boss, 2026-10-13): names, pins, archive and new chats, and how they are kept. */
class TabBookTest {
    @Test fun aChatsKeyIsNoOtherTabsKey() {
        assertEquals("chat:c1", chatKey("c1")); assertEquals("c1", chatId("chat:c1"))
        for (other in listOf(OMA_TAB, PHONE_THREAD, "mac:t1", "chat:", "")) assertNull(other, chatId(other))
    }

    @Test fun aNameIsOneShortLineAndABlankOneGivesTheTabItsOwnBack() {
        assertEquals("Dock icons", tabName("  Dock \n icons "))
        assertEquals(TAB_NAME_CHARS, tabName("x".repeat(80))!!.length)
        assertNull(tabName(" \n "))
        val named = TabMeta().renamed("mac:t1", "Menu fix")
        assertEquals(mapOf("mac:t1" to "Menu fix"), named.names)
        assertEquals(TabMeta(), named.renamed("mac:t1", "  "))
    }

    @Test fun oneNamedByOmaKeepsTheNameHeGaveIt() {
        assertEquals("Mine", TabMeta().renamed("chat:c1", "Mine").titled("chat:c1", "Worktree commands").names["chat:c1"])
        assertEquals("Worktree commands", TabMeta().titled("chat:c1", "Worktree commands").names["chat:c1"])
    }

    @Test fun pinningAndArchiving() {
        val pinned = TabMeta().pinnedAs("a", true).pinnedAs("b", true).pinnedAs("a", true)
        assertEquals(listOf("a", "b"), pinned.pinned)
        assertEquals(listOf("b"), pinned.pinnedAs("a", false).pinned)
        // Archiving a pinned tab unpins it; restoring brings it back unpinned.
        val away = pinned.archivedAs("a", 500)
        assertEquals(mapOf("a" to 500L), away.archived); assertEquals(listOf("b"), away.pinned)
        assertEquals(emptyMap<String, Long>(), away.restored("a").archived)
        assertSame(away, away.restored("zzz"))
    }

    @Test fun aNewChatLivesOnTheMacThreadItsWordsLastWentTo() {
        val started = TabMeta().started("c1", 10).started("c1", 99)
        assertEquals(listOf(NewChat("c1", 10)), started.chats); assertFalse(started.chat("c1")!!.bound)
        // Waiting for him to name a project, then on the thread made there, which ends the wait.
        val asking = started.asking("c1", 42)
        assertEquals(42L, asking.chat("c1")!!.asking)
        val onMac = asking.onMac("c1", "t9")
        assertTrue(onMac.chat("c1")!!.bound); assertEquals(0L, onMac.chat("c1")!!.asking)
        assertEquals("c1", onMac.chatOnMac("t9")!!.id); assertNull(onMac.chatOnMac("t1"))
        // A turn there that ends at its agent's limit goes on to another agent's thread, and the chat with it.
        val moved = onMac.onMac("c1", "t10")
        assertEquals("c1", moved.chatOnMac("t10")!!.id); assertNull(moved.chatOnMac("t9"))
        assertEquals(0L, started.asking("c1", 42).asking("c1", 0).chat("c1")!!.asking)
        assertEquals("s-1", started.heard("c1", "s-1").chat("c1")!!.front)
        assertEquals("", started.heard("c1", "s-1").heard("c1", null).chat("c1")!!.front)
        assertEquals(onMac.chat("c1"), onMac.chatOf("chat:c1")); assertNull(onMac.chatOf(PHONE_THREAD))
    }

    @Test fun newChatIdsAreNeverTakenTwice() {
        val id = newChatId(1_800_000_000_000L, emptyList())
        assertTrue(id.startsWith("c")); assertTrue(id.length <= ChatHistory.MAX_CHAT)
        assertNotEquals(id, newChatId(1_800_000_000_000L, listOf(id)))
        assertEquals(id + "b", newChatId(1_800_000_000_000L, listOf(id, id + "a")))
    }

    @Test fun plusOpensTheEmptyChatThereIsRatherThanMakingAnother() {
        val meta = TabMeta().started("c1", 1).started("c2", 2)
        assertEquals("c2", emptyChat(meta, emptyList()))
        assertEquals("c1", emptyChat(meta, listOf(ChatTask(5, QUICK_AGENT, "hi", chat = "c2"))))
        assertNull(emptyChat(meta.archivedAs("chat:c1", 3), listOf(ChatTask(5, QUICK_AGENT, "hi", chat = "c2"))))
        assertNull(emptyChat(meta.onMac("c1", "t9"), listOf(ChatTask(5, QUICK_AGENT, "hi", chat = "c2"))))
    }

    @Test fun anArchivedTabComesBackWithNewWorkOrAnApprovalAndNotOtherwise() {
        assertFalse(revived(100, newest = 90, needsOk = false))
        assertFalse(revived(100, newest = null, needsOk = false))
        assertTrue(revived(100, newest = 101, needsOk = false))
        assertTrue(revived(100, newest = null, needsOk = true))
        val ff4 = AgentThread("mac:t1", "FF4", ThreadState.Idle, "", "done")
        val pond = AgentThread("mac:t2", "Pond", ThreadState.NeedsOk, "", "needs your OK")
        val meta = TabMeta().archivedAs("mac:t1", 100).archivedAs("mac:t2", 100).archivedAs(PHONE_THREAD, 100)
        val tasks = listOf(ChatTask(50, "→ FF4", "old", thread = "t1", threadName = "FF4", status = TaskStatus.Done), ChatTask(150, "Claude Code", "new", status = TaskStatus.Running))
        assertEquals(setOf("mac:t2", PHONE_THREAD), revivals(meta, listOf(ff4, pond), tasks))
        assertEquals(mapOf("mac:t1" to 100L), meta.revive(listOf(ff4, pond), tasks).archived)
    }

    @Test fun theBookSurvivesARestartAndWritesOnlyWhatIsSet() {
        assertEquals("{}", tabMetaText(TabMeta()))
        assertEquals(TabMeta(), tabMetaFrom(null)); assertEquals(TabMeta(), tabMetaFrom("not json"))
        val chats = TabMeta().started("c1", 1).started("c2", 2).started("c3", 3).asking("c1", 7).onMac("c2", "t9").heard("c3", "s-3")
        val meta = chats.renamed("chat:c1", "Dock").pinnedAs("mac:t1", true).archivedAs(PHONE_THREAD, 77)
        assertEquals(meta, tabMetaFrom(tabMetaText(meta)))
        // An unbound chat keeps the shape it was made with: only its id and when.
        val saved = JSONObject(tabMetaText(TabMeta().started("c1", 1)))
        assertEquals(setOf("chats"), saved.keys().asSequence().toSet())
        assertEquals(setOf("id", "made"), saved.getJSONArray("chats").getJSONObject(0).keys().asSequence().toSet())
        // A chat a phone builder took in an earlier version loads unbound, its builder forgotten.
        assertEquals(listOf(NewChat("c1", 1, front = "s-1")), tabMetaFrom("""{"chats":[{"id":"c1","made":1,"builder":"B","front":"s-1"}]}""").chats)
        // A chat with an id too long to be ours, or none, is left out.
        assertEquals(emptyList<NewChat>(), tabMetaFrom("""{"chats":[{"id":""},{"id":"${"x".repeat(80)}"}]}""").chats)
    }

    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L

    /** The strip as the chat shows it at [now], with [wanted] open: the same functions, in the same order, as the screen. */
    private fun strip(meta: TabMeta, tasks: List<ChatTask>, now: Long, wanted: String? = null): List<String> {
        val all = agentThreads(tasks, BuilderNow(), MacView(reachable = true), chats = meta.chats, names = meta.names)
        val book = meta.settled(all, tasks, now)
        val live = liveThreads(all, emptyMap(), now, listed = true, book.pinned.toSet(), book.archived.keys)
        return shownThreads(live, book.pinned, all, book.archived.keys, wanted).map { it.key }
    }

    /**
     * The Critic's case (2026-10-14): ＋, renamed "Harbour", then the OMA tab: the chat left the strip at once, was
     * not in Archived, and with a newer empty chat ＋ opened only that one, so Harbour could not be reached at all.
     */
    @Test fun anEmptyChatHeRenamedStaysOnTheStripWhenAnotherTabIsOpen() {
        val harbour = TabMeta().started("c1", t0).renamed("chat:c1", "Harbour", t0 + 60_000)
        assertEquals(t0 + 60_000, harbour.chat("c1")!!.used)
        assertTrue("chat:c1" in strip(harbour, emptyList(), t0 + 2 * 60_000, wanted = null))
        val newer = harbour.started("c2", t0 + 3 * 60_000)
        assertEquals("c2", emptyChat(newer, emptyList()))
        assertEquals(listOf("chat:c1", "chat:c2"), strip(newer, emptyList(), t0 + 4 * 60_000))
    }

    /** A new chat lives [AGENT_TILE_IDLE_HOURS] from its last use, as any thread does, and not a moment more. */
    @Test fun aNewChatRestsTwelveHoursFromItsLastUse() {
        val idle = AGENT_TILE_IDLE_HOURS * hour
        val empty = TabMeta().started("c1", t0)
        assertEquals(listOf("chat:c1"), strip(empty, emptyList(), t0 + idle - 1))
        assertEquals(emptyList<String>(), strip(empty, emptyList(), t0 + idle))
        // Renamed later, it counts from then.
        val named = empty.renamed("chat:c1", "Harbour", t0 + 5 * hour)
        assertEquals(listOf("chat:c1"), strip(named, emptyList(), t0 + 5 * hour + idle - 1))
        // Written in later, from its newest entry.
        val said = listOf(ChatTask(t0 + 8 * hour, QUICK_AGENT, "hi", result = "Hello.", status = TaskStatus.Done, chat = "c1"))
        assertEquals(listOf("chat:c1"), strip(empty, said, t0 + 8 * hour + idle - 1))
        assertEquals(emptyList<String>(), strip(empty, said, t0 + 8 * hour + idle))
    }

    /** Rested, a chat he named or wrote in goes to Archived, where he can bring it back; an empty, unnamed one is let go. */
    @Test fun aRestedChatHeNamedOrWroteInIsArchivedNotLost() {
        val later = t0 + AGENT_TILE_IDLE_HOURS * hour + 1
        val meta = TabMeta().started("c1", t0).started("c2", t0).started("c3", t0).renamed("chat:c1", "Harbour", t0)
        val tasks = listOf(ChatTask(t0, QUICK_AGENT, "hi", result = "Hello.", status = TaskStatus.Done, chat = "c2"))
        val all = agentThreads(tasks, BuilderNow(), MacView(), chats = meta.chats, names = meta.names)
        val after = meta.settled(all, tasks, later)
        assertEquals(mapOf("chat:c1" to later, "chat:c2" to later), after.archived)
        assertEquals(listOf("c1", "c2"), after.chats.map { it.id })
        // Not brought back by what it held before; restored, it is on the strip again and rests its hours from then.
        assertEquals(after, after.settled(all, tasks, later + 1))
        val back = after.restored("chat:c1", later + 2)
        assertEquals(listOf("chat:c1"), strip(back, tasks, later + 3))
        assertEquals(emptyList<String>(), strip(back, tasks, later + 2 + AGENT_TILE_IDLE_HOURS * hour))
        // Before its time nothing is touched.
        assertEquals(meta, meta.settled(all, tasks, later - 2))
    }

    /** A pinned chat never leaves, and a bound one rests as its thread on the Mac does, not here. */
    @Test fun aPinnedOrBoundChatIsNotRetired() {
        val later = t0 + 3 * AGENT_TILE_IDLE_HOURS * hour
        val meta = TabMeta().started("c1", t0).pinnedAs("chat:c1", true).started("c2", t0).onMac("c2", "t9")
        val all = agentThreads(emptyList(), BuilderNow(), MacView(), chats = meta.chats, names = meta.names)
        assertEquals(meta, meta.settled(all, emptyList(), later))
        assertEquals(listOf("chat:c1"), strip(meta, emptyList(), later).filter { it == "chat:c1" })
    }

    @Test fun whenHeLastUsedAChatIsKeptAndOmasNamingIsNotAUse() {
        val meta = TabMeta().started("c1", 1).renamed("chat:c1", "Harbour", 50)
        assertEquals(meta, tabMetaFrom(tabMetaText(meta)))
        assertEquals(0L, TabMeta().started("c1", 1).titled("chat:c1", "Worktrees").chat("c1")!!.used)
        assertEquals(0L, TabMeta().started("c1", 1).renamed("chat:c1", "Harbour").chat("c1")!!.used)
    }
}
