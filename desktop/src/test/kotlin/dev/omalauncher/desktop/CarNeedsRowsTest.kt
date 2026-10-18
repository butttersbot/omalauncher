package dev.omalauncher.desktop

import dev.omalauncher.canvas.CanvasCommands
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Boss, 2026-10-17: the car's Needs you is at least two rows, so an answer's note never takes its only row. */
class CarNeedsRowsTest {
    private val needs = moduleKey(ModuleKind.Needs)!!
    private fun car(home: File = Files.createTempDirectory("soma-car").toFile()) = ScreenCanvas(File(home, "screens/car.json"), CAR_SCREEN, CAR_COLUMNS, CAR_ROWS, "macpro").also { it.load() }
    private fun cell(c: ScreenCanvas, key: String = needs) = c.page.pages.flatMap { it.items }.first { it.key == key }.cell!!
    private fun run(c: ScreenCanvas, words: String, actor: String = "grok") = CanvasCommands.run(words.split(' '), c, "r-" + words.hashCode(), actor)

    @Test fun placedOrResizedAsALineItStillHasTwoRows() {
        val c = car()
        val placed = run(c, "place needs line top-left")
        assertTrue(placed.toString(), placed.getBoolean("ok"))
        assertEquals(2, cell(c).h)
        assertTrue(run(c, "resize needs dot").getBoolean("ok"))
        assertEquals(2, cell(c).h)
        val line = run(c, "resize needs line")
        assertTrue(line.toString(), line.getBoolean("ok"))
        assertEquals(2, cell(c).h)
        assertTrue(line.toString(), "2 rows tall" in line.getString("did"))
        // Anything else may still be one row on the car, and Needs you elsewhere may be one row.
        assertTrue(run(c, "place threads line bottom-left").getBoolean("ok"))
        assertEquals(1, cell(c, moduleKey(ModuleKind.Threads)!!).h)
        val desk = ScreenCanvas(File(Files.createTempDirectory("soma-desk").toFile(), "screens/desk.json"), "desk-macbook", 12, 8, "macpro").also { it.load() }
        assertTrue(run(desk, "place needs line top-left").getBoolean("ok"))
        assertEquals(1, cell(desk).h)
    }

    @Test fun withNoRoomForTwoRowsItIsRefusedNotSquashed() {
        val c = car()
        // Every row but one taken across the page: Needs you can't have two rows there.
        for (y in 0 until CAR_ROWS - 1) assertTrue(c.apply(CanvasVerb.Place("w$y.owl", ItemKind.Widget, "main", Cell(0, y, CAR_COLUMNS, 1)), "hand") == null)
        val placed = run(c, "place needs line --page Car")
        // On a new page, then, still two rows; never one.
        if (placed.getBoolean("ok")) assertEquals(2, cell(c).h)
    }

    @Test fun aTemplateWithAOneRowNeedsYouLaysItOnTheCarAtTwo() {
        val home = Files.createTempDirectory("soma-car-tpl").toFile()
        val shelf = FileShelf(home)
        val desk = ScreenCanvas(File(home, "screens/desk.json"), "desk-macbook", 12, 8, "macpro").also { it.load() }
        assertTrue(CanvasCommands.run("place needs line top-left".split(' '), desk, "t1", "agent:t", shelf).getBoolean("ok"))
        assertTrue(CanvasCommands.run("template-save Drive".split(' '), desk, "t2", "agent:t", shelf).getBoolean("ok"))
        val c = car(home)
        val applied = CanvasCommands.run("template-apply Drive".split(' '), c, "t3", "grok, approved by him", shelf)
        assertTrue(applied.toString(), applied.getBoolean("ok"))
        assertEquals(2, cell(c).h)
    }

    @Test fun anOldOneRowNeedsYouIsGrownWhenTheCarIsRead() {
        val home = Files.createTempDirectory("soma-car-old").toFile()
        val old = car(home)
        // As an older service laid it: one row, with room below.
        assertTrue(old.apply(CanvasVerb.Place(needs, ItemKind.Module, "main", Cell(0, 0, 4, 1)), "agent:t") == null)
        assertEquals(Cell(0, 0, 4, 2), cell(car(home)))
        // With something right below it, it grows up instead; with no room either way it stays.
        val home2 = Files.createTempDirectory("soma-car-old2").toFile()
        val old2 = car(home2)
        assertTrue(old2.apply(CanvasVerb.Place(needs, ItemKind.Module, "main", Cell(0, 2, 4, 1)), "agent:t") == null)
        assertTrue(old2.apply(CanvasVerb.Place("x.owl", ItemKind.Widget, "main", Cell(0, 3, 4, 1)), "hand") == null)
        assertEquals(Cell(0, 1, 4, 2), cell(car(home2)))
        val home3 = Files.createTempDirectory("soma-car-old3").toFile()
        val old3 = car(home3)
        assertTrue(old3.apply(CanvasVerb.Place(needs, ItemKind.Module, "main", Cell(0, 1, 4, 1)), "agent:t") == null)
        assertTrue(old3.apply(CanvasVerb.Place("a.owl", ItemKind.Widget, "main", Cell(0, 0, 4, 1)), "hand") == null)
        assertTrue(old3.apply(CanvasVerb.Place("b.owl", ItemKind.Widget, "main", Cell(0, 2, 4, 1)), "hand") == null)
        assertEquals(Cell(0, 1, 4, 1), cell(car(home3)))
        // Grown once: read again, nothing more is written.
        val v = car(home).log.version
        assertEquals(v, car(home).log.version)
    }

    /** Round-1 review: undoing an old arrangement put a one-row Needs you back until the next start. */
    @Test fun anUndoNeverLeavesItOneRow() {
        val home = Files.createTempDirectory("soma-car-undo").toFile()
        val old = car(home)
        assertTrue(old.apply(CanvasVerb.Place(needs, ItemKind.Module, "main", Cell(0, 0, 4, 1)), "agent:t", "r1") == null)
        assertTrue(old.apply(CanvasVerb.Move(needs, "main", Cell(4, 3, 4, 1)), "agent:t", "r2") == null)
        val c = car(home)
        assertEquals(2, cell(c).h)
        val undo = CanvasCommands.run(listOf("undo"), c, "u1", "agent:t")
        assertTrue(undo.toString(), undo.getBoolean("ok"))
        assertEquals(0, cell(c).x)
        assertEquals(2, cell(c).h)
    }
}
