package dev.omalauncher.widgets

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.RestorePoint
import dev.omalauncher.canvas.ScreenRules
import dev.omalauncher.canvas.SentVerb
import dev.omalauncher.canvas.SYNC_WHOLE
import dev.omalauncher.canvas.SyncWire
import dev.omalauncher.canvas.takeWhole
import dev.omalauncher.canvas.appendSent
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.canvas.replayLog
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.LoggedVerb
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PinnedApp
import dev.omalauncher.widgets.layout.VerbLog
import dev.omalauncher.widgets.layout.VerbOrigin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync between the Fold and the service's copy of its home (Soma Canvas slice 8b, PRD §8), two writers on one screen: Boss's
 * hand (and the launcher's tile keeper) on the Fold, an agent on the service. The service here is the same pure function the
 * real one runs (appendSent), so what converges here converges there.
 */
class FoldSyncTest {
    private val chrome = PinnedApp("app:chrome#0", "Chrome", "com.android.chrome/.Main", 0)
    private val saved = Notebook(rows = 8, pages = listOf(
        NotebookPage("p1", "Today", listOf(NotebookItem(chrome.key, ItemKind.App, Cell(0, 0, 1, 1), app = chrome), NotebookItem("clock.owl", ItemKind.Widget, Cell(1, 0, 3, 2)))),
        NotebookPage("p2", "Agents", listOf(NotebookItem("thread:t1", ItemKind.Thread, Cell(0, 0, 3, 3)))),
    ))
    private val needs = moduleKey(ModuleKind.Needs)!!

    /** The service: the Fold's screen as a canonical log; online or not. */
    private inner class Service {
        var log = VerbLog("fold-test")
        var online = true
        val notes = ArrayList<String>()
        fun exchange(since: Long, sent: List<SentVerb>): JSONObject? {
            if (!online) return null
            val whole = since == SYNC_WHOLE
            if (!whole && since > log.version) return JSONObject().put("refused", 409).put("error", "the service has no version $since of \"fold-test\"")
            val mine = sent.map { it.copy(origin = it.origin.copy(device = "fold")) }
            val (next, took) = if (whole) takeWhole(log, mine, 6, 8, VerbOrigin("fold", "hand")) else appendSent(log, mine, 6, 8)
            log = next
            took.mapNotNull { it.why }.let { notes += it }
            return SyncWire.tail(log, if (whole) 0 else since).put("notes", org.json.JSONArray(took.mapNotNull { it.why }))
        }

        /** An agent on the service arranging the Fold's page, as `soma canvas` would through the door. */
        fun agent(words: String): JSONObject {
            val pad = object : dev.omalauncher.canvas.CanvasPad {
                override val rules = ScreenRules(pageId = { n -> "p" + ((n.pages.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1) })
                override fun notebook() = replayLog(log, 6, 8)
                override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
                    val (next, took) = appendSent(log, verbs.map { SentVerb(log.version, request, VerbOrigin("macpro", actor), it) }, 6, 8)
                    log = next
                    return took.firstOrNull { it.version == null }?.why
                }
                override fun lastRestorePoint(): RestorePoint? = null
                override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? = null
            }
            return CanvasCommands.run(words.split(' '), pad, "agent-1", "agent:t9")
        }
    }

    private class Fold(saved: Notebook, marked: (String) -> Boolean = { false }) {
        var kept: String? = null
        val notebook = LiveNotebook().apply { open(saved, kept = true) }
        val sync = FoldSync(notebook, "fold-test", { kept }, { kept = it }, marked = marked)
    }

    @Test fun aRefusedRoundNeverUndoesHisChangesAndTheyGoUpOnceTaken() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        fold.notebook.setCell("clock.owl", Cell(3, 6, 3, 2)); fold.notebook.save()
        fold.notebook.forget("thread:t1")
        // The service refuses, for any reason (another owner, another grid, a failure, too many asks): home stays as he left it.
        for (refusal in listOf(JSONObject().put("refused", 403).put("error", "that screen is another device's"),
                JSONObject().put("refused", 409).put("error", "that screen's grid is 6x9"), JSONObject().put("refused", 500).put("error", "the service failed"),
                JSONObject().put("refused", 429).put("error", "too many"))) {
            val notes = fold.sync.round { _, _ -> refusal }!!
            assertTrue(notes.toString(), notes.single().startsWith("the canvas service refused"))
            assertEquals(Cell(3, 6, 3, 2), fold.notebook.cell("clock.owl"))
            assertNull(fold.notebook.pageOf("thread:t1"))
        }
        // Taken: his changes go up.
        fold.sync.round(service::exchange)
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
        assertEquals(Cell(3, 6, 3, 2), replayLog(service.log, 6, 8).pages[0].items.first { it.key == "clock.owl" }.cell)
    }

    @Test fun somethingHePutsWhereAnAgentsModuleIsGoingIsNeverCoveredByIt() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        assertTrue(service.agent("place needs card bottom-left --page Today").getBoolean("ok"))
        val needsAt = replayLog(service.log, 6, 8).pages[0].items.first { it.key == needs }.cell!!
        // While the round is out he drags the clock onto those cells.
        fold.sync.round { since, sent ->
            fold.notebook.setCell("clock.owl", needsAt); fold.notebook.save()
            service.exchange(since, sent)
        }
        fun overlapping() = fold.notebook.cell(needs)?.overlaps(fold.notebook.cell("clock.owl")!!) == true
        assertFalse(overlapping())
        // Offline a while: still nothing on top of anything.
        service.online = false
        repeat(3) { fold.sync.round(service::exchange); assertFalse(overlapping()) }
        service.online = true
        repeat(2) { fold.sync.round(service::exchange) }
        assertFalse(overlapping())
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
    }

    @Test fun aPageWithHisInkIsNeverTakenAway() {
        val service = Service()
        val inked = setOf("p3")
        val fold = Fold(saved.copy(pages = saved.pages + NotebookPage("p3", "Sketches")), marked = { it in inked })
        fold.sync.round(service::exchange)
        assertTrue(service.agent("page-remove Sketches").getBoolean("ok"))
        val notes = fold.sync.round(service::exchange)!!
        assertTrue(fold.notebook.snapshot().pages.any { it.id == "p3" })
        assertTrue(notes.toString(), notes.any { "notes or ink" in it })
    }

    private fun items(n: Notebook) = n.pages.associate { p -> p.id to p.items.filter { it.kind != ItemKind.App && it.kind != ItemKind.Folder }.associate { it.key to it.cell } }

    @Test fun theFoldSeedsTheServiceThenTheyConvergeWithAnAgentAndHisHand() {
        val service = Service()
        val fold = Fold(saved)
        // First round: home goes up as verbs, its app as the cell it takes.
        assertEquals(emptyList<String>(), fold.sync.round(service::exchange))
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
        assertTrue(replayLog(service.log, 6, 8).pages[0].items.any { it.key == chrome.key && it.cell == Cell(0, 0, 1, 1) })
        // An agent, on the service, puts Needs you on Today: it lands clear of the app and the clock, as the service sees them.
        assertTrue(service.agent("place needs card top-left --page Today").getBoolean("ok"))
        // Meanwhile, by hand, he moves the clock down.
        fold.notebook.setCell("clock.owl", Cell(0, 4, 3, 2)); fold.notebook.save()
        fold.sync.round(service::exchange)
        // Both are on both: the agent's module came through LiveNotebook, his move went up.
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
        assertTrue(fold.notebook.cell(needs) != null)
        assertEquals(Cell(0, 4, 3, 2), fold.notebook.cell("clock.owl"))
        // A round with nothing new changes nothing anywhere.
        val before = service.log.version
        fold.sync.round(service::exchange)
        assertEquals(before, service.log.version)
    }

    @Test fun offlineTheFoldKeepsWorkingAndALateMoveIsReResolved() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        service.online = false
        // He moves the clock to where the agent is about to put something.
        fold.notebook.setCell("clock.owl", Cell(3, 6, 3, 2)); fold.notebook.save()
        assertNull(fold.sync.round(service::exchange))
        assertEquals(Cell(3, 6, 3, 2), fold.notebook.cell("clock.owl"))
        assertTrue(service.agent("place timeline card bottom-right --page Today").getBoolean("ok"))
        // Back: the late move lands as near as there is room, and the Fold shows where it went.
        service.online = true
        val notes = fold.sync.round(service::exchange)!!
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
        assertTrue(notes.toString(), notes.any { "as near as there was room" in it })
        assertFalse(fold.notebook.cell("clock.owl")!!.overlaps(fold.notebook.cell(moduleKey(ModuleKind.Timeline)!!)!!))
    }

    @Test fun theLaunchersTilesGoUpAsItsOwnAndItsEchoesChangeNothing() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        // The keeper places a new thread's tile.
        assertTrue(fold.notebook.placeThreadTile("thread:t2", "p2", Cell(3, 0, 3, 3)))
        fold.sync.round(service::exchange)
        assertEquals("launcher", service.log.entries.last { (it.verb as? CanvasVerb.Place)?.key == "thread:t2" }.origin.actor)
        // Its echo comes back and nothing moves.
        val cells = items(fold.sync.home())
        fold.sync.round(service::exchange)
        assertEquals(cells, items(fold.sync.home()))
    }

    @Test fun aNewerBuildsVerbIsKeptAndNotAppliedAndAGonePageDropsALateVerb() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        // From a newer build: in the log, never on home.
        service.log = service.log.copy(entries = service.log.entries + LoggedVerb(service.log.version + 1, "x", VerbOrigin("macpro", "agent"), CanvasVerb.Unknown(JSONObject().put("verb", "page.teleport")), schema = 99))
        val home = items(fold.sync.home())
        fold.sync.round(service::exchange)
        assertEquals(home, items(fold.sync.home()))
        assertEquals(99, fold.sync.sync.synced.entries.last().schema)
        // A move onto a page the service no longer has is dropped, and said.
        service.online = false
        fold.notebook.setCell("thread:t1", Cell(3, 3, 3, 3)); fold.notebook.save()
        fold.sync.round(service::exchange)
        service.log = appendSent(service.log, listOf(SentVerb(service.log.version, "a", VerbOrigin("macpro", "agent"), CanvasVerb.Remove("thread:t1")), SentVerb(service.log.version, "a", VerbOrigin("macpro", "agent"), CanvasVerb.PageRemove("p2"))), 6, 8).first
        service.online = true
        val notes = fold.sync.round(service::exchange)!!
        assertTrue(notes.toString(), notes.any { it.startsWith("dropped") })
    }

    @Test fun aWidgetWaitingForItsCellIsNeverForgotten() {
        val service = Service()
        val fold = Fold(saved)
        fold.notebook.adopt(listOf("new.owl" to 2))
        fold.notebook.expect("agent-made.owl", "p1")
        repeat(2) { fold.sync.round(service::exchange) }
        assertEquals("p2", fold.notebook.pageOf("new.owl"))
        assertEquals("p1", fold.notebook.pageOf("agent-made.owl"))
    }

    @Test fun thingsThatChangePlacesConvergeAndThenStayQuiet() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        // The agent swaps the clock and Needs you through a free spot: three exact moves.
        assertTrue(service.agent("place needs card bottom-left --page Today").getBoolean("ok"))
        fold.sync.round(service::exchange)
        val clock = fold.notebook.cell("clock.owl")!!
        val needsAt = fold.notebook.cell(needs)!!
        service.log = appendSent(service.log, listOf(
            SentVerb(service.log.version, "swap", VerbOrigin("macpro", "agent"), CanvasVerb.Move(needs, "p1", Cell(3, 5, 3, 2))),
            SentVerb(service.log.version, "swap", VerbOrigin("macpro", "agent"), CanvasVerb.Move("clock.owl", "p1", needsAt)),
            SentVerb(service.log.version, "swap", VerbOrigin("macpro", "agent"), CanvasVerb.Move(needs, "p1", clock)),
        ), 6, 8).first
        fold.sync.round(service::exchange)
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
        val quiet = service.log.version
        repeat(3) { fold.sync.round(service::exchange) }
        assertEquals(quiet, service.log.version)
    }

    @Test fun aChangeMadeWhileARoundIsOnItsWayIsKeptAndGoesNext() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        fold.sync.round { since, sent ->
            // He moves the clock while the round is out.
            fold.notebook.setCell("clock.owl", Cell(3, 6, 3, 2)); fold.notebook.save()
            service.exchange(since, sent)
        }
        assertEquals(Cell(3, 6, 3, 2), fold.notebook.cell("clock.owl"))
        fold.sync.round(service::exchange)
        assertEquals(items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
    }

    @Test fun syncNeverPinsOrUnpinsAnApp() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        service.log = appendSent(service.log, listOf(
            SentVerb(service.log.version, "x", VerbOrigin("macpro", "agent"), CanvasVerb.Remove(chrome.key)),
            SentVerb(service.log.version, "x", VerbOrigin("macpro", "agent"), CanvasVerb.Place("app:other#0", ItemKind.App, "p1", Cell(5, 7, 1, 1))),
        ), 6, 8).first
        fold.sync.round(service::exchange)
        assertEquals("p1", fold.notebook.pageOf(chrome.key))
        assertNull(fold.notebook.pageOf("app:other#0"))
    }

    @Test fun anAnswerThatSkipsVersionsIsNotTaken() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        val have = fold.sync.sync.synced.version
        assertNull(fold.sync.round { _, _ -> SyncWire.tail(service.log.append("x", VerbOrigin("macpro", "agent"), CanvasVerb.PageMake("p9", "Nine")).append("x", VerbOrigin("macpro", "agent"), CanvasVerb.PageRename("p9", "Ten")), have + 1) })
        assertEquals(have, fold.sync.sync.synced.version)
    }

    @Test fun aServiceThatLostThisScreensCopyIsSentItWholeAgain() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        val lost = Service()
        // The Mac's copy is gone: it has no version of what the phone holds.
        val refusal = { since: Long, sent: List<SentVerb> -> if (since > lost.log.version) JSONObject().put("refused", 409).put("error", "the service has no version $since of \"fold-test\"") else lost.exchange(since, sent) }
        assertNull(fold.sync.round(refusal))
        fold.sync.round(refusal)
        assertEquals(items(fold.sync.home()), items(replayLog(lost.log, 6, 8)))
    }

    @Test fun aServiceThatWentBackToAnOlderCopyBecomesHisHomeAgainNothingHeRemovedComesBack() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        val backup = service.log
        // He takes the clock off, renames a page and makes one; it all goes up.
        fold.notebook.forget("clock.owl")
        fold.notebook.renamePage("p2", "Threads")
        fold.sync.round(service::exchange)
        assertNull(replayLog(service.log, 6, 8).pages[0].items.firstOrNull { it.key == "clock.owl" })
        // The Mac is put back to an earlier backup: its copy is older than what the phone has.
        service.log = backup
        assertNull(fold.sync.round(service::exchange))
        val notes = fold.sync.round(service::exchange)!!
        assertTrue(notes.toString(), notes.any { "older copy" in it })
        // Home is as he left it, and the service's copy is home: the clock stays gone, the page keeps its new name.
        assertNull(fold.notebook.pageOf("clock.owl"))
        assertEquals("Threads", fold.notebook.snapshot().pages.first { it.id == "p2" }.name)
        val copy = replayLog(service.log, 6, 8)
        assertEquals(items(fold.sync.home()), items(copy))
        assertEquals(fold.sync.home().pages.map { it.id to it.name }, copy.pages.map { it.id to it.name })
        // And quiet after.
        val quiet = service.log.version
        repeat(2) { fold.sync.round(service::exchange) }
        assertEquals(quiet, service.log.version)
    }

    @Test fun aServiceThatWentBackAndWentOnIsNoticedAndMadeHisHomeAgain() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        val backup = service.log
        fold.notebook.forget("clock.owl")
        fold.sync.round(service::exchange)
        // Put back to the backup, then an agent writes before the phone's next round: the same version, other content.
        service.log = backup
        assertTrue(service.agent("page-make Extra").getBoolean("ok"))
        assertEquals(fold.sync.sync.synced.version, service.log.version)
        val (target, notes) = fold.sync.sync.round(fold.sync.pending(), service::exchange)!!
        assertNull(target)
        assertTrue(notes.toString(), notes.single().contains("older copy"))
        fold.sync.round(service::exchange)
        val copy = replayLog(service.log, 6, 8)
        assertEquals(items(fold.sync.home()), items(copy))
        assertEquals(fold.sync.home().pages.map { it.id to it.name }, copy.pages.map { it.id to it.name })
        assertNull(fold.notebook.pageOf("clock.owl"))
        val quiet = service.log.version
        repeat(2) { fold.sync.round(service::exchange) }
        assertEquals(quiet, service.log.version)
    }

    @Test fun somethingHeRemovesWhileAnAgentMovesItStaysGone() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        assertTrue(service.agent("move clock bottom-right --page Today").getBoolean("ok") || run {
            service.log = appendSent(service.log, listOf(SentVerb(service.log.version, "m", VerbOrigin("macpro", "agent"), CanvasVerb.Move("clock.owl", "p1", Cell(3, 6, 3, 2)))), 6, 8).first; true
        })
        fold.sync.round { since, sent ->
            fold.notebook.forget("clock.owl")
            service.exchange(since, sent)
        }
        assertNull(fold.notebook.pageOf("clock.owl"))
        fold.sync.round(service::exchange)
        assertNull(fold.notebook.pageOf("clock.owl"))
        assertTrue(replayLog(service.log, 6, 8).pages.none { p -> p.items.any { it.key == "clock.owl" } })
    }

    @Test fun aLatePageRemovalSaysWhatItTookWithIt() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        service.online = false
        fold.notebook.deletePage("p2", emptySet(), move = false)
        fold.sync.round(service::exchange)
        assertTrue(service.agent("place needs card --page Agents").getBoolean("ok"))
        service.online = true
        val notes = fold.sync.round(service::exchange)!!
        assertTrue(notes.toString(), notes.any { "took" in it && "put on it since" in it })
    }

    @Test fun manyRandomRoundsAlwaysConverge() {
        for (seed in 1..120) {
            val r = java.util.Random(seed.toLong())
            val service = Service()
            val fold = Fold(saved)
            fold.sync.round(service::exchange)
            val words = listOf("place needs card", "place threads line", "place timeline card-wide", "move needs top-right", "move threads bottom", "resize needs line", "remove threads", "place pocket dot right-of needs")
            repeat(12) {
                when (r.nextInt(5)) {
                    0 -> service.online = !service.online
                    1 -> service.agent(words[r.nextInt(words.size)] + if (r.nextBoolean()) " --page Today" else "")
                    2 -> fold.notebook.setCell("clock.owl", Cell(r.nextInt(4), r.nextInt(7), 3, 2)).also { fold.notebook.save() }
                    3 -> fold.notebook.cell(needs)?.let { fold.notebook.setCell(needs, it.copy(x = r.nextInt(4), y = r.nextInt(7))); fold.notebook.save() }
                    else -> fold.sync.round(service::exchange)
                }
            }
            service.online = true
            repeat(4) { fold.sync.round(service::exchange) }
            val v = service.log.version
            fold.sync.round(service::exchange)
            assertEquals("seed $seed", items(fold.sync.home()), items(replayLog(service.log, 6, 8)))
            assertEquals("seed $seed kept writing when quiet", v, service.log.version)
        }
    }

        @Test fun aHomeThatWasSetAsideNeitherSendsNorTakes() {
        val service = Service()
        val notebook = LiveNotebook().apply { open(saved, kept = false) }
        val sync = FoldSync(notebook, "fold-test", { null }, {})
        assertNull(sync.round(service::exchange))
        assertEquals(0L, service.log.version)
    }

    @Test fun whatTheServiceGaveIsKeptAcrossARestart() {
        val service = Service()
        val fold = Fold(saved)
        fold.sync.round(service::exchange)
        val again = FoldSync(fold.notebook, "fold-test", { fold.kept }, {})
        assertEquals(service.log.version, again.sync.synced.version)
        assertTrue(again.pending().isEmpty())
    }
}
