package dev.omalauncher.widgets.layout

import dev.omalauncher.widgets.Cell
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasVerbsTest {
    private val hand = VerbOrigin("fold", "hand")
    private val car = Notebook(rows = 5, columns = 8, pages = listOf(NotebookPage("drive", "Drive", listOf(NotebookItem("needs", ItemKind.Widget, Cell(0, 0, 4, 3))))))

    @Test fun exactVerbsChangeTheNotebookExactly() {
        var n = applyVerb(car, CanvasVerb.PageMake("more", "More")).also { assertTrue(it.applied) }.notebook
        n = applyVerb(n, CanvasVerb.Place("watch", ItemKind.Widget, "more", Cell(4, 0, 4, 2))).also { assertTrue(it.applied) }.notebook
        assertEquals(Cell(4, 0, 4, 2), n.pages[1].items.single().cell)
        // A move to another page takes the item off the first.
        n = applyVerb(n, CanvasVerb.Move("needs", "more", Cell(0, 0, 4, 3))).also { assertTrue(it.applied) }.notebook
        assertTrue(n.pages[0].items.isEmpty())
        assertEquals(2, n.pages[1].items.size)
        n = applyVerb(n, CanvasVerb.PageRename("more", "Watch")).notebook
        assertEquals("Watch", n.pages[1].name)
        n = applyVerb(n, CanvasVerb.Remove("watch")).notebook
        assertEquals(listOf("needs"), n.pages[1].items.map { it.key })
        n = applyVerb(n, CanvasVerb.PageMake("first", "First", after = null)).notebook
        assertEquals(listOf("drive", "more", "first"), n.pages.map { it.id })
        n = applyVerb(n, CanvasVerb.PageRemove("more")).notebook
        assertEquals(listOf("drive", "first"), n.pages.map { it.id })
    }

    @Test fun aPageMovesWithEverythingOnItAndUndoesBack() {
        var n = applyVerb(car, CanvasVerb.PageMake("b", "B")).notebook
        n = applyVerb(n, CanvasVerb.PageMake("c", "C")).notebook
        // First, after another, last: what is on it goes with it.
        val first = applyVerb(n, CanvasVerb.PageMove("c", PAGE_START)).also { assertTrue(it.applied) }.notebook
        assertEquals(listOf("c", "drive", "b"), first.pages.map { it.id })
        assertEquals(listOf("drive", "c", "b"), applyVerb(n, CanvasVerb.PageMove("c", "drive")).notebook.pages.map { it.id })
        val last = applyVerb(n, CanvasVerb.PageMove("drive")).notebook
        assertEquals(listOf("b", "c", "drive"), last.pages.map { it.id })
        assertEquals(listOf("needs"), last.pages.last().items.map { it.key })
        // Undone, it is back where it was.
        val back = dev.omalauncher.canvas.inverseOf(n, CanvasVerb.PageMove("drive")).fold(last) { at, v -> applyVerb(at, v).notebook }
        assertEquals(n, back)
        val json = VerbJson.verb(CanvasVerb.PageMove("c", "drive"))
        assertEquals(CanvasVerb.PageMove("c", "drive"), VerbJson.verbOf(json))
        assertEquals(CanvasVerb.PageMove("c"), VerbJson.verbOf(VerbJson.verb(CanvasVerb.PageMove("c"))))
    }

    @Test fun aVerbThatCantApplyChangesNothingAndSaysWhy() {
        for (bad in listOf(
            CanvasVerb.Place("x", ItemKind.Widget, "drive", Cell(2, 1, 2, 2)), // onto Needs you
            CanvasVerb.Place("x", ItemKind.Widget, "drive", Cell(6, 0, 3, 1)), // off the 8-column grid
            CanvasVerb.Place("x", ItemKind.Widget, "drive", Cell(0, 4, 1, 2)), // below the 5 rows
            CanvasVerb.Place("x", ItemKind.Widget, "gone", Cell(5, 0, 1, 1)),
            CanvasVerb.Move("nobody", "drive", Cell(5, 0, 1, 1)),
            CanvasVerb.Remove("nobody"),
            CanvasVerb.PageMake("drive", "Again"),
            CanvasVerb.PageMake("x", "X", after = "gone"),
            CanvasVerb.PageRemove("gone"),
            CanvasVerb.PageMove("gone"),
            CanvasVerb.PageMove("drive", after = "gone"),
            CanvasVerb.PageMove("drive", after = "drive"),
            CanvasVerb.Unknown(JSONObject().put("verb", "page.teleport")),
            CanvasVerb.Place("newapp", ItemKind.App, "drive", Cell(5, 0, 1, 1)), // an app needs its entry
            CanvasVerb.Place("needs", ItemKind.Thread, "drive", Cell(5, 0, 1, 1)), // already a widget here
        )) {
            val r = applyVerb(car, bad)
            assertFalse(bad.toString(), r.applied)
            assertTrue(r.why.isNotEmpty())
            assertEquals(car, r.notebook)
        }
        // Moving an item onto its own old spot, or resizing it in place, is fine: it doesn't collide with itself.
        assertTrue(applyVerb(car, CanvasVerb.Move("needs", "drive", Cell(0, 0, 3, 3))).applied)
    }

    @Test fun theLogKeepsOrderAndRequestsAndRoundTrips() {
        val log = VerbLog("car")
            .append("r1", VerbOrigin("macbook", "agent:t1a2b3c4d"), CanvasVerb.PageMake("more", "More", "drive"))
            .append("r1", VerbOrigin("macbook", "agent:t1a2b3c4d"), CanvasVerb.Place("watch", ItemKind.Widget, "more", Cell(0, 0, 4, 2)))
            .append("r2", hand, CanvasVerb.Move("watch", "more", Cell(4, 0, 4, 2)))
        assertEquals(listOf(1L, 2L, 3L), log.entries.map { it.version })
        assertEquals(2, log.request("r1").size)
        assertEquals(log, VerbJson.logOf(VerbJson.log(log)))
        assertNull(VerbJson.logOf("not json"))
        // A log holding a verb from a newer build still equals its own round trip (the keeper compares echoes).
        val mixed = log.copy(entries = log.entries + LoggedVerb(4, "r3", hand, CanvasVerb.Unknown(JSONObject().put("verb", "page.teleport").put("to", "måne")), schema = 2))
        assertEquals(mixed, VerbJson.logOf(VerbJson.log(mixed)))
    }

    /** One counter per screen: versions read back in order, each once, and the next follows the highest. */
    @Test fun versionsOnlyGoUp() {
        fun entry(v: Long) = org.json.JSONObject().put("version", v).put("request", "r").put("origin", org.json.JSONObject().put("device", "d").put("actor", "a")).put("verb", VerbJson.verb(CanvasVerb.Remove("x$v")))
        val text = org.json.JSONObject().put("screen", "car").put("entries", org.json.JSONArray(listOf(entry(1), entry(3), entry(2), entry(3)))).toString()
        val log = VerbJson.logOf(text)!!
        assertEquals(listOf(1L, 2L, 3L), log.entries.map { it.version })
        assertEquals(3L, log.version)
        assertEquals(4L, log.append("r", hand, CanvasVerb.Remove("y")).version)
        // An entry with no verb keeps its version, as a verb this build can't read.
        val bare = org.json.JSONObject().put("screen", "car").put("entries", org.json.JSONArray(listOf(org.json.JSONObject().put("version", 9).put("request", "r").put("origin", org.json.JSONObject().put("device", "d").put("actor", "a"))))).toString()
        assertTrue(VerbJson.logOf(bare)!!.entries.single().verb is CanvasVerb.Unknown)
    }

    @Test fun aNewerOrUnknownVerbIsKeptNeverMisread() {
        val newer = JSONObject().put("verb", "module.place").put("key", "x").put("kind", "Widget").put("page", "drive").put("cell", VerbJson.cell(Cell(5, 0, 1, 1)))
        assertTrue(VerbJson.verbOf(newer, schema = VERB_SCHEMA + 1) is CanvasVerb.Unknown)
        assertTrue(VerbJson.verbOf(JSONObject().put("verb", "module.place").put("kind", "Hologram")) is CanvasVerb.Unknown)
        assertTrue(VerbJson.verbOf(JSONObject().put("nothing", 1)) is CanvasVerb.Unknown)
        // Kept as it came, so it goes back out unchanged.
        val unknown = JSONObject().put("verb", "page.teleport").put("to", "mars")
        assertEquals(unknown.toString(), VerbJson.verb(VerbJson.verbOf(unknown)).toString())
        val text = VerbJson.log(VerbLog("fold", listOf(LoggedVerb(7, "r", hand, CanvasVerb.Unknown(unknown), schema = 9))))
        val back = VerbJson.logOf(text)!!.entries.single()
        assertEquals(9, back.schema)
        assertTrue(back.verb is CanvasVerb.Unknown)
    }
}
