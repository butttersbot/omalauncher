package dev.omalauncher.widgets

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.assist.macThreadKey
import dev.omalauncher.widgets.annotations.Box
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.NotebookStore
import dev.omalauncher.widgets.layout.PinnedApp
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The thread tiles on home (Boss, 2026-10-13: "dedicated page", "12 hours is fine"). This is the one writer
 * to his pages that runs by itself, so what it may write is pinned here: only thread tiles, only on the page
 * named Agents, and that page itself when it is missing. Nothing on any other page is placed, moved or removed.
 */
class AgentTilesTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun thread(key: String, state: ThreadState = ThreadState.Working, worked: Long? = now, name: String = key.substringAfterLast(':')) =
        AgentThread(key, name, state, "doing", state.name.lowercase(), worked)

    private val phone = thread(PHONE_THREAD, name = "Phone")
    private val ff4 = thread(macThreadKey("t-ff4"), name = "FF4")
    private val pond = thread(macThreadKey("t-pond"), name = "Pond")
    private fun tile(thread: AgentThread) = threadTileKey(thread.key)

    @Test fun aTileIsKnownByItsThreadsKey() {
        assertEquals("thread:phone", threadTileKey(PHONE_THREAD)); assertEquals("thread:mac:t-ff4", tile(ff4))
        assertEquals("mac:t-ff4", tileThread(tile(ff4))); assertEquals(PHONE_THREAD, tileThread("thread:phone"))
        for (other in listOf("clock.owl", "app:chrome#0", "linux:foot", "folder:a", "thread:", "agent-claude.owl")) assertNull(other, tileThread(other))
        assertTrue(isThreadTile("thread:phone")); assertFalse(isThreadTile("clock.owl")); assertFalse(isThreadTile("app:thread:x"))
        assertEquals(12, AGENT_TILE_IDLE_HOURS)
    }

    @Test fun theAgentsPageIsTheFirstPageOfThatName() {
        assertNull(agentsPage(listOf(PageLabel("p1", "Today"), PageLabel("p2", "Agents of change"))))
        assertEquals("p3", agentsPage(listOf(PageLabel("p1", "Today"), PageLabel("p3", "Agents"), PageLabel("p4", "Agents"))))
        // As a page's name is matched everywhere else, capitals do not count: no second page is made beside "agents".
        assertEquals("p2", agentsPage(listOf(PageLabel("p1", "Today"), PageLabel("p2", "agents"))))
    }

    @Test fun aTileNeverLeavesWhileItsThreadIsBusyAndLeavesTwelveHoursAfter() {
        for (state in ThreadState.entries.filter { it.active && it != ThreadState.Attention }) {
            assertEquals(state.name, TileLife.Stays, tileLife(state, now - 400 * hour, now))
            assertEquals(state.name, TileLife.Stays, tileLife(state, null, now))
        }
        assertEquals(listOf(ThreadState.Working, ThreadState.Queued, ThreadState.NeedsOk, ThreadState.Attention), ThreadState.entries.filter { it.active })
        assertEquals(TileLife.Stays, tileLife(ThreadState.Idle, now, now))
        assertEquals(TileLife.Stays, tileLife(ThreadState.Idle, now - 11 * hour + 1, now))
        assertEquals(TileLife.Fading, tileLife(ThreadState.Idle, now - 11 * hour, now))
        assertEquals(TileLife.Fading, tileLife(ThreadState.Idle, now - 12 * hour + 1, now))
        assertEquals(TileLife.Gone, tileLife(ThreadState.Idle, now - 12 * hour, now))
        // A failure nothing waits on leaves as an idle thread does (Boss, 2026-10-13: tiles fall off after inactivity).
        assertEquals(TileLife.Stays, tileLife(ThreadState.Attention, now - 11 * hour + 1, now))
        assertEquals(TileLife.Gone, tileLife(ThreadState.Attention, now - 12 * hour, now))
        assertEquals(TileLife.Stays, tileLife(ThreadState.Attention, null, now))
        // An idle thread that never worked has nothing to show.
        assertEquals(TileLife.Gone, tileLife(ThreadState.Idle, null, now))
    }

    private fun plan(
        threads: List<AgentThread>, placed: Map<String, String> = emptyMap(), agents: String? = "pa", stamps: Map<String, TileStamp> = emptyMap(),
        at: Long = now, listed: Boolean = true,
    ) = agentTilePlan(threads, placed, agents, stamps, at, listed)

    @Test fun aThreadGetsATileWhenItStartsWorkAndKeepsItWhileItIsBusy() {
        val first = plan(listOf(phone, ff4, thread(macThreadKey("t-idle"), ThreadState.Idle, worked = null)))
        assertEquals(listOf(tile(phone), tile(ff4)), first.add); assertTrue(first.remove.isEmpty())
        assertEquals(setOf(tile(phone), tile(ff4)), first.show.keys)
        // Once placed nothing more is asked for, however long it stays busy.
        val placed = mapOf(tile(phone) to "pa", tile(ff4) to "pa")
        val later = plan(listOf(phone, ff4.copy(state = ThreadState.NeedsOk, worked = now)), placed, stamps = first.stamps, at = now + 100 * hour)
        assertTrue(later.add.isEmpty()); assertTrue(later.remove.isEmpty())
        assertEquals(ThreadState.NeedsOk, later.show.getValue(tile(ff4)).thread?.state)
        assertFalse(later.show.getValue(tile(ff4)).fading)
    }

    @Test fun anIdleThreadsTileFadesInItsLastHourThenLeavesTheAgentsPage() {
        val idle = ff4.copy(state = ThreadState.Idle, worked = now)
        val placed = mapOf(tile(ff4) to "pa")
        assertFalse(plan(listOf(idle), placed, at = now + 10 * hour).show.getValue(tile(ff4)).fading)
        val fading = plan(listOf(idle), placed, at = now + 11 * hour + 5)
        assertTrue(fading.show.getValue(tile(ff4)).fading); assertTrue(fading.remove.isEmpty())
        val gone = plan(listOf(idle), placed, at = now + 12 * hour)
        assertEquals(listOf(tile(ff4)), gone.remove); assertTrue(gone.show.isEmpty()); assertTrue(gone.add.isEmpty())
        // A thread that worked lately gets its tile though the phone never saw it busy; an old one does not.
        assertEquals(listOf(tile(ff4)), plan(listOf(idle), at = now + 3 * hour).add)
        assertTrue(plan(listOf(idle), at = now + 13 * hour).add.isEmpty())
        // When it was last seen busy is remembered, so a thread whose own clock says less still gets its twelve hours from then.
        val seen = plan(listOf(ff4.copy(worked = null)), placed).stamps
        assertEquals(now, seen.getValue(ff4.key).busy)
        assertTrue(plan(listOf(ff4.copy(state = ThreadState.Idle, worked = null)), placed, stamps = seen, at = now + 5 * hour).remove.isEmpty())
        assertEquals(listOf(tile(ff4)), plan(listOf(ff4.copy(state = ThreadState.Idle, worked = null)), placed, stamps = seen, at = now + 12 * hour).remove)
    }

    @Test fun aTileHeCarriedToAnotherPageIsHisAndIsNeverRemoved() {
        val idle = ff4.copy(state = ThreadState.Idle, worked = now)
        val his = mapOf(tile(ff4) to "p1")
        val old = plan(listOf(idle), his, at = now + 500 * hour)
        assertTrue(old.remove.isEmpty()); assertTrue(old.add.isEmpty())
        // It keeps showing how the thread stands, and does not fade: it is not about to leave.
        assertEquals(AgentTile("FF4", idle, fading = false), old.show.getValue(tile(ff4)))
        // Not when its thread is gone from the Mac either.
        val orphan = plan(emptyList(), his, stamps = mapOf(ff4.key to TileStamp("FF4", now, placed = true)), at = now + 500 * hour, listed = true)
        assertTrue(orphan.remove.isEmpty()); assertEquals(AgentTile("FF4", null), orphan.show.getValue(tile(ff4)))
        // With no Agents page at all, every tile there is is his.
        assertTrue(plan(listOf(idle), mapOf(tile(ff4) to "pa"), agents = null, at = now + 500 * hour).remove.isEmpty())
        // Carried back to the Agents page it is the launcher's to keep again.
        assertEquals(listOf(tile(ff4)), plan(listOf(idle), mapOf(tile(ff4) to "pa"), at = now + 500 * hour).remove)
    }

    @Test fun aTileWaitsForTheMacsListBeforeItsThreadIsTakenForGone() {
        val placed = mapOf(tile(ff4) to "pa")
        val stamps = mapOf(ff4.key to TileStamp("FF4", now, placed = true))
        // After a restart the Mac has not answered yet: the tile stays, drawn by the name remembered.
        val waiting = plan(listOf(phone), placed, stamps = stamps, at = now + hour, listed = false)
        assertTrue(waiting.remove.isEmpty()); assertEquals(AgentTile("FF4", null), waiting.show.getValue(tile(ff4)))
        assertEquals(stamps.getValue(ff4.key), waiting.stamps.getValue(ff4.key))
        // The list is heard and the thread is not on it, or nothing was heard for twelve hours: it goes.
        assertEquals(listOf(tile(ff4)), plan(listOf(phone), placed, stamps = stamps, at = now + hour, listed = true).remove)
        assertEquals(listOf(tile(ff4)), plan(listOf(phone), placed, stamps = stamps, at = now + 12 * hour, listed = false).remove)
        // A tile nothing at all is remembered of goes too, unless it is his.
        assertEquals(listOf(tile(pond)), plan(listOf(phone), mapOf(tile(pond) to "pa"), listed = false).remove)
        assertTrue(plan(listOf(phone), mapOf(tile(pond) to "p1"), listed = false).remove.isEmpty())
    }

    @Test fun aTileHeTookOffStaysOffUntilItsThreadNextStartsWork() {
        val placed = mapOf(tile(ff4) to "pa")
        var stamps = plan(listOf(ff4), placed).stamps
        assertTrue(stamps.getValue(ff4.key).placed)
        // He removes it, or deletes the page it was on, while the thread works: it is not put back.
        var next = plan(listOf(ff4), emptyMap(), stamps = stamps, at = now + 1000)
        assertTrue(next.add.isEmpty()); assertTrue(next.show.isEmpty())
        assertEquals(TileHidden.UntilIdle, next.stamps.getValue(ff4.key).hidden)
        stamps = next.stamps
        // Nor while that same work goes on, nor once it rests.
        next = plan(listOf(ff4.copy(state = ThreadState.NeedsOk)), emptyMap(), stamps = stamps, at = now + 2000)
        assertTrue(next.add.isEmpty()); stamps = next.stamps
        next = plan(listOf(ff4.copy(state = ThreadState.Idle)), emptyMap(), stamps = stamps, at = now + 3000)
        assertTrue(next.add.isEmpty()); assertEquals(TileHidden.UntilWork, next.stamps.getValue(ff4.key).hidden); stamps = next.stamps
        // The next start of work is when a tile is next needed.
        next = plan(listOf(ff4), emptyMap(), stamps = stamps, at = now + 4000)
        assertEquals(listOf(tile(ff4)), next.add); assertEquals(TileHidden.No, next.stamps.getValue(ff4.key).hidden)
        // A tile the launcher itself took off is not one he took off: its thread gets a tile again as any idle thread would.
        val idle = ff4.copy(state = ThreadState.Idle, worked = now)
        val expired = plan(listOf(idle), placed, stamps = plan(listOf(idle), placed).stamps, at = now + 12 * hour)
        assertEquals(listOf(tile(ff4)), expired.remove); assertFalse(expired.stamps.getValue(ff4.key).placed)
        assertEquals(TileHidden.No, plan(listOf(idle), emptyMap(), stamps = expired.stamps, at = now + 12 * hour + 1).stamps.getValue(ff4.key).hidden)
        assertEquals(TileHidden.UntilWork, hiddenNext(TileStamp("x", now, placed = true), there = false, active = false))
        assertEquals(TileHidden.No, hiddenNext(TileStamp("x", now, placed = true, hidden = TileHidden.UntilWork), there = true, active = false))
        assertEquals(TileHidden.No, hiddenNext(null, there = false, active = true))
    }

    @Test fun aBusyThreadIsNotStampedAgainAtEveryLook() {
        val placed = mapOf(tile(ff4) to "pa")
        val working = ff4.copy(worked = null)
        val first = plan(listOf(working), placed).stamps
        assertEquals(first, plan(listOf(working), placed, stamps = first, at = now + 2000).stamps)
        assertEquals(now + STAMP_EVERY_MS, plan(listOf(working), placed, stamps = first, at = now + STAMP_EVERY_MS).stamps.getValue(ff4.key).busy)
    }

    @Test fun theStampsSurviveARestart() {
        val stamps = mapOf(ff4.key to TileStamp("FF4", now, placed = true, hidden = TileHidden.UntilWork), PHONE_THREAD to TileStamp("Phone"), pond.key to TileStamp("Pond", now - 5, hidden = TileHidden.UntilIdle))
        assertEquals(stamps, tileStampsFrom(tileStampsText(stamps)))
        assertEquals(emptyMap<String, TileStamp>(), tileStampsFrom(null)); assertEquals(emptyMap<String, TileStamp>(), tileStampsFrom("not json"))
        assertEquals(mapOf("a" to TileStamp("")), tileStampsFrom("""{"a":{},"b":5}"""))
    }

    // What may be written, and where.

    /**
     * The pin (Boss, 2026-10-13): whatever the pages are called, wherever tiles lie and whatever the plan says,
     * every change is a thread tile's on the first page named Agents, or the making of that page when there is
     * none. No other page is named by any change.
     */
    @Test fun everyWriteIsAThreadTilesOnThePageNamedAgents() {
        val random = Random(20261013)
        val names = listOf("Today", "Work", "Agents", "agents", "Agents 2", "Trip", "", "Page 3", "AGENTS")
        repeat(4000) {
            val pages = List(random.nextInt(1, 7)) { PageLabel("p$it", names.random(random)) }
            val keys = List(random.nextInt(0, 6)) { index -> listOf("thread:mac:t$index", "thread:phone", "clock.owl", "app:chrome#0", "folder:a", "thread:").random(random) }.distinct()
            val placed = keys.filter { random.nextBoolean() }.associateWith { pages.random(random).id }
            val plan = TilePlan(emptyMap(), keys.filter { random.nextBoolean() }, (keys + placed.keys).filter { random.nextBoolean() }, emptyMap())
            val agents = pages.firstOrNull { it.name.equals("Agents", ignoreCase = true) }?.id
            val writes = agentWrites(pages, placed, plan)
            for (write in writes) {
                when (write) {
                    AgentWrite.MakePage -> {
                        assertNull("a second Agents page", agents); assertEquals(listOf<AgentWrite>(AgentWrite.MakePage), writes)
                    }
                    is AgentWrite.Place -> {
                        assertEquals(agents, write.page); assertTrue(write.tile, isThreadTile(write.tile))
                        assertFalse("a tile is placed, never moved", write.tile in placed)
                    }
                    is AgentWrite.Remove -> {
                        assertEquals(agents, write.page); assertTrue(write.tile, isThreadTile(write.tile))
                        assertEquals("only where it is: on the Agents page", agents, placed[write.tile])
                    }
                }
            }
            if (agents == null) assertTrue(writes.isEmpty() || writes == listOf(AgentWrite.MakePage))
        }
    }

    @Test fun thePageIsMadeOnlyWhenATileNeedsIt() {
        val pages = listOf(PageLabel("p1", "Today"))
        assertEquals(emptyList<AgentWrite>(), agentWrites(pages, emptyMap(), TilePlan(emptyMap(), emptyList(), listOf("thread:phone"), emptyMap())))
        assertEquals(listOf<AgentWrite>(AgentWrite.MakePage), agentWrites(pages, emptyMap(), TilePlan(emptyMap(), listOf("thread:phone"), emptyList(), emptyMap())))
        assertEquals(
            listOf<AgentWrite>(AgentWrite.Place("p2", "thread:phone")),
            agentWrites(pages + PageLabel("p2", "Agents"), emptyMap(), TilePlan(emptyMap(), listOf("thread:phone"), emptyList(), emptyMap())),
        )
    }

    // The keeper, on a real notebook.

    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val his =
        Notebook(
            rows = 9,
            pages =
                listOf(
                    NotebookPage("p1", "Today", listOf(NotebookItem("clock.owl", ItemKind.Widget, Cell(0, 0, 3, 2)), NotebookItem(chrome.key, ItemKind.App, Cell(0, 2, 1, 1), app = chrome))),
                    NotebookPage("p2", "Trip", listOf(NotebookItem("map.owl", ItemKind.Widget, Cell(0, 0, 6, 4)))),
                ),
        )

    private inner class Home(start: Notebook = his, kept: Boolean = true, stamps: String? = null) {
        val saved = ArrayList<String>()
        var stampText = stamps
        val logged = ArrayList<String>()
        val notebook = LiveNotebook(persist = { saved += it }).also { it.open(start, kept) }
        val keeper = AgentTileKeeper(notebook, stampText, { stampText = it }, { logged += it })
        var clock = now

        fun look(vararg threads: AgentThread, listed: Boolean = true, scraps: (String) -> List<Box>? = { emptyList() }) {
            clock += 2000
            keeper.keep(threads.toList(), listed, clock, scraps)
        }

        fun page(name: String) = notebook.snapshot().pages.first { it.name == name }

        /** As the next start of the launcher would have it. */
        fun restarted() = Home(NotebookStore.read(saved.last())!!, stamps = stampText)
    }

    private fun overlaps(page: NotebookPage) = page.items.mapNotNull { it.cell }.let { cells -> cells.indices.any { a -> cells.indices.any { b -> a < b && cells[a].overlaps(cells[b]) } } }

    @Test fun theFirstTileMakesTheAgentsPageAndTilesGoOnlyThere() {
        val home = Home()
        home.look(phone.copy(state = ThreadState.Idle, worked = null))
        assertEquals(his, home.notebook.snapshot()); assertTrue(home.saved.isEmpty())
        home.look(phone, ff4, pond)
        val after = home.notebook.snapshot()
        assertEquals(listOf("Today", "Trip", "Agents"), after.pages.map { it.name })
        // His own pages are exactly as they were.
        assertEquals(his.pages, after.pages.take(2))
        val agents = home.page("Agents")
        assertEquals(setOf("thread:phone", "thread:mac:t-ff4", "thread:mac:t-pond"), agents.items.map { it.key }.toSet())
        assertTrue(agents.items.all { it.kind == ItemKind.Thread && it.cell?.w == 3 && it.cell?.h == 3 })
        // All one size, two to a row from the top left, on nothing else.
        assertEquals(setOf(Cell(0, 0, 3, 3), Cell(3, 0, 3, 3), Cell(0, 3, 3, 3)), agents.items.mapNotNull { it.cell }.toSet())
        assertFalse(overlaps(agents))
        assertEquals(setOf("thread:phone", "thread:mac:t-ff4", "thread:mac:t-pond"), home.keeper.tiles.keys.toSet())
        assertEquals(listOf("added the Agents page"), home.logged)
        // Looking again changes nothing and writes nothing.
        val writes = home.saved.size
        repeat(5) { home.look(phone, ff4, pond) }
        assertEquals(after, home.notebook.snapshot()); assertEquals(writes, home.saved.size)
    }

    @Test fun aRestartFindsTheTilesWhereTheyWereAndAddsNoSecondOnes() {
        val home = Home()
        home.look(phone, ff4)
        val before = home.notebook.snapshot()
        val again = home.restarted()
        assertEquals(before, again.notebook.snapshot())
        // Before the Mac has answered: the builder alone is known, and FF4's tile waits where it is.
        again.look(phone, listed = false)
        assertEquals(before, again.notebook.snapshot())
        assertEquals(AgentTile("FF4", null), again.keeper.tiles["thread:mac:t-ff4"])
        again.look(phone, ff4)
        assertEquals(before, again.notebook.snapshot())
        assertEquals(1, again.notebook.snapshot().pages.count { it.name == "Agents" })
        assertTrue(again.saved.isEmpty())
    }

    @Test fun aFullAgentsPageShowsNoNewTileAndNothingGoesElsewhere() {
        val wall = NotebookItem("wall.owl", ItemKind.Widget, Cell(0, 0, 6, 8))
        val start = his.copy(pages = his.pages + NotebookPage("pa", "Agents", listOf(wall)))
        val home = Home(start)
        repeat(3) { home.look(phone, ff4) }
        assertEquals(start, home.notebook.snapshot()); assertTrue(home.saved.isEmpty())
        // Said once for each tile, not at every look.
        assertEquals(listOf("no room on the Agents page for thread:phone: it is not shown", "no room on the Agents page for thread:mac:t-ff4: it is not shown"), home.logged)
        // Room for one, beside the place kept for a widget whose file is missing: one is placed there, and the other still is not.
        val roomy = Home(his.copy(pages = his.pages + NotebookPage("pa", "Agents", listOf(wall.copy(cell = Cell(0, 0, 6, 5)), NotebookItem("gone.owl", ItemKind.Widget, Cell(0, 5, 3, 2))))))
        roomy.look(phone, ff4)
        val agents = roomy.page("Agents")
        assertEquals(listOf(Cell(3, 5, 3, 3)), agents.items.filter { it.kind == ItemKind.Thread }.mapNotNull { it.cell })
        assertFalse(overlaps(agents))
        assertEquals(his.pages, roomy.notebook.snapshot().pages.take(2))
    }

    @Test fun aTileKeepsClearOfNotesAndStickersAndWaitsWhileTheyAreRead() {
        val home = Home(his.copy(pages = his.pages + NotebookPage("pa", "Agents")))
        home.look(phone, scraps = { null })
        assertTrue(home.page("Agents").items.isEmpty())
        // A note lying over the top left: the tile goes beside it.
        val board = home.notebook.board("pa")
        home.look(phone, scraps = { page -> if (page == "pa") listOf(board.box(Cell(0, 0, 2, 2))) else throw AssertionError("looked at $page") })
        assertEquals(Cell(2, 0, 3, 3), home.page("Agents").items.single().cell)
    }

    @Test fun aDeletedAgentsPageComesBackOnlyWhenATileIsNextNeeded() {
        val home = Home()
        home.look(phone, ff4)
        val agents = home.notebook.pages.last().id
        // He deletes the page and has its tiles taken off home with it.
        assertNotNull(home.notebook.deletePage(agents, widgets = setOf("clock.owl", "map.owl"), move = false))
        assertEquals(his, home.notebook.snapshot())
        repeat(3) { home.look(phone, ff4) }
        assertEquals(his, home.notebook.snapshot())
        // The threads rest, and one starts work again: its tile is needed, so the page is made for it.
        home.look(phone.copy(state = ThreadState.Idle), ff4.copy(state = ThreadState.Idle))
        assertEquals(his, home.notebook.snapshot())
        home.look(phone.copy(state = ThreadState.Idle), ff4)
        assertEquals(listOf("Today", "Trip", "Agents"), home.notebook.snapshot().pages.map { it.name })
        assertEquals(listOf("thread:mac:t-ff4"), home.page("Agents").items.map { it.key })
        assertEquals(his.pages, home.notebook.snapshot().pages.take(2))
    }

    @Test fun deletingThePageAndMovingItsTilesMakesThemHis() {
        val home = Home()
        home.look(phone)
        val agents = home.notebook.pages.last().id
        val gone = home.notebook.deletePage(agents, widgets = setOf("clock.owl", "map.owl"), move = true)!!
        assertEquals(listOf("thread:phone"), gone.moved)
        assertEquals("p2", home.notebook.pageOf("thread:phone"))
        val moved = home.notebook.snapshot()
        // It is on a page of his now, by his choice: it stays for good, and no Agents page is made for it.
        home.clock += 500 * hour
        repeat(3) { home.look(phone.copy(state = ThreadState.Idle, worked = now)) }
        assertEquals(moved, home.notebook.snapshot())
    }

    @Test fun aRenamedPageIsHisAndANewAgentsPageIsMadeForTheNextTile() {
        val home = Home()
        home.look(phone)
        val first = home.notebook.pages.last().id
        assertTrue(home.notebook.renamePage(first, "Robots"))
        home.clock += 500 * hour
        home.look(phone.copy(state = ThreadState.Idle, worked = now))
        // The tile on the page he renamed is never taken off, and nothing new is made for threads that have one.
        assertEquals(listOf("Today", "Trip", "Robots"), home.notebook.snapshot().pages.map { it.name })
        assertEquals(listOf("thread:phone"), home.page("Robots").items.map { it.key })
        home.look(phone.copy(state = ThreadState.Idle, worked = now), ff4)
        assertEquals(listOf("Today", "Trip", "Robots", "Agents"), home.notebook.snapshot().pages.map { it.name })
        assertEquals(listOf("thread:mac:t-ff4"), home.page("Agents").items.map { it.key })
        assertEquals(listOf("thread:phone"), home.page("Robots").items.map { it.key })
    }

    @Test fun aTileHeCarriedAwayStaysAndAnIdleOneOnTheAgentsPageLeaves() {
        val home = Home()
        home.look(phone, ff4)
        home.notebook.move("thread:mac:t-ff4", "p1")
        val idle = listOf(phone.copy(state = ThreadState.Idle, worked = now), ff4.copy(state = ThreadState.Idle, worked = now))
        home.clock = now + 11 * hour
        home.look(*idle.toTypedArray())
        assertTrue(home.keeper.tiles.getValue("thread:phone").fading); assertFalse(home.keeper.tiles.getValue("thread:mac:t-ff4").fading)
        home.clock = now + 12 * hour
        home.look(*idle.toTypedArray())
        assertTrue(home.page("Agents").items.isEmpty())
        assertEquals("p1", home.notebook.pageOf("thread:mac:t-ff4"))
        assertEquals(setOf("thread:mac:t-ff4"), home.keeper.tiles.keys.toSet())
        // The page stays, empty, as every page does: only he deletes pages.
        assertEquals(listOf("Today", "Trip", "Agents"), home.notebook.snapshot().pages.map { it.name })
    }

    @Test fun nothingIsPlacedInASessionThatIsNotWrittenOrNotYetRead() {
        val unkept = Home(kept = false)
        unkept.look(phone, ff4)
        assertEquals(his, unkept.notebook.snapshot()); assertNull(unkept.stampText); assertTrue(unkept.keeper.tiles.isEmpty())
        val unread = LiveNotebook()
        AgentTileKeeper(unread, null, { throw AssertionError("saved $it") }).keep(listOf(phone), true, now) { emptyList() }
        assertEquals(1, unread.pages.size); assertTrue(unread.threadTiles().isEmpty())
    }

    /** Whatever the threads do, for however long, his own pages hold exactly what they held. */
    @Test fun noRunOfLooksEverChangesAPageThatIsNotAgents() {
        val random = Random(45)
        val home = Home()
        val all = listOf(phone, ff4, pond, thread(macThreadKey("t-a")), thread(macThreadKey("t-b")), thread(macThreadKey("t-c")), thread(macThreadKey("t-d")), thread(macThreadKey("t-e")))
        repeat(600) {
            home.clock += random.nextLong(0, 3 * hour)
            val threads = all.filter { it.key == PHONE_THREAD || random.nextInt(4) > 0 }.map { it.copy(state = ThreadState.entries.random(random), worked = home.clock - random.nextLong(0, 20 * hour)) }
            home.look(*threads.toTypedArray(), listed = random.nextBoolean())
            val pages = home.notebook.snapshot().pages
            assertEquals(his.pages, pages.take(2))
            assertEquals(listOf("Today", "Trip", "Agents").take(pages.size), pages.map { it.name })
            pages.getOrNull(2)?.let { agents ->
                assertTrue(agents.items.all { it.kind == ItemKind.Thread && it.cell != null })
                assertFalse(overlaps(agents))
            }
        }
        assertTrue(home.logged.any { it.startsWith("no room on the Agents page") })
    }

    @Test fun theBoardTellsAThreadTileByItsOwnKind() {
        val home = Home()
        home.look(phone)
        val tiles = home.notebook.board(home.notebook.pages.last().id).tiles
        assertEquals(listOf(dev.omalauncher.widgets.annotations.PageTile("thread:phone", Cell(0, 0, 3, 3), "agent")), tiles)
        assertEquals("agent", AGENT_TILE_KIND)
    }

    @Test fun theSmallLineSaysWhereItRunsAndHowLongAgo() {
        assertEquals("THIS PHONE · NOW", tileWhere(AgentTile("Phone", phone), now))
        assertEquals("MAC · NOW", tileWhere(AgentTile("FF4", ff4.copy(worked = now - 9 * hour)), now))
        val idle = ff4.copy(state = ThreadState.Idle)
        assertEquals("MAC · 2 MIN", tileWhere(AgentTile("FF4", idle.copy(worked = now - 150_000)), now))
        assertEquals("MAC · 11 HR", tileWhere(AgentTile("FF4", idle.copy(worked = now - 11 * hour - 5)), now))
        assertEquals("MAC · NOW", tileWhere(AgentTile("FF4", idle.copy(worked = now - 20_000)), now))
        assertEquals("MAC · 3 DAYS", tileWhere(AgentTile("FF4", idle.copy(worked = now - 80 * hour)), now))
        assertEquals("MAC · OUT OF REACH", tileWhere(AgentTile("FF4", ff4.copy(reachable = false)), now))
        assertEquals("MAC · NOT HEARD FROM", tileWhere(AgentTile("FF4", null), now))
    }

    /** The launcher's one writer to his pages reaches the notebook through these calls and no others. */
    @Test fun theKeeperChangesTheNotebookOnlyAsTheWritesSay() {
        val keeper = dev.omalauncher.theme.pack.moduleFile("src/main/kotlin/dev/omalauncher/widgets/AgentTileKeeper.kt").readLines()
            .map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }.joinToString("\n")
        assertEquals(
            listOf("notebook.threadTiles(", "notebook.addPage(", "notebook.renamePage(", "notebook.pageOf(", "notebook.forget(", "notebook.threadTiles(", "notebook.board(", "notebook.placeThreadTile("),
            Regex("""\bnotebook\.\w+\(""").findAll(keeper).map { it.value }.toList(),
        )
        assertTrue(keeper.contains("notebook.renamePage(made, AGENTS_PAGE)"))
        assertTrue(keeper.contains("if (isThreadTile(write.tile) && notebook.pageOf(write.tile) == write.page) notebook.forget(write.tile)"))
    }
}
