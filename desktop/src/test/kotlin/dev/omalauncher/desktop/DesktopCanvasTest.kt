package dev.omalauncher.desktop

import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.VerbJson
import dev.omalauncher.widgets.layout.VerbLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Soma desktop's canvas as verbs (slice 5): what a new Soma starts with, and what is kept, refused and replayed. */
class DesktopCanvasTest {
    private val dir = Files.createTempDirectory("soma-canvas").toFile()
    private val file = dir.resolve("canvas.json")

    private fun fresh() = DesktopCanvas(file, "macbook-pro").also { it.load() }

    @Test fun aNewSomaStartsWithTheChatAndTheModulesBesideIt() {
        val canvas = fresh()
        assertEquals(listOf(CHAT_BLOCK, "module:needs", "module:threads", "module:timeline", "module:computers", "module:newthread"), canvas.items().map { it.key })
        assertEquals(Cell(0, 0, 5, DESKTOP_ROWS), canvas.items().first().cell)
        // Every starting block is a verb in the log, from Soma itself, and nothing overlaps or leaves the grid.
        assertEquals(7, canvas.log.entries.size)
        assertTrue(canvas.log.entries.all { it.origin.device == "macbook-pro" && it.origin.actor == "soma" })
        val cells = canvas.items().mapNotNull { it.cell }
        for (a in cells) for (b in cells) if (a !== b) assertFalse("$a overlaps $b", a.overlaps(b))
        assertTrue(cells.all { it.right <= DESKTOP_COLUMNS && it.bottom <= DESKTOP_ROWS })
    }

    @Test fun whatIsKeptIsTheLogAndThePageIsItReplayed() {
        val canvas = fresh()
        assertTrue(canvas.apply(CanvasVerb.Move("module:threads", DESKTOP_PAGE, Cell(9, 0, 3, 2))).applied)
        assertTrue(canvas.apply(CanvasVerb.Remove("module:computers")).applied)
        assertTrue(canvas.add("module:pocket").applied)
        val again = DesktopCanvas(file, "macbook-pro").also { it.load() }
        assertEquals(canvas.page, again.page)
        assertEquals(canvas.log, again.log)
        assertEquals(canvas.page, replay(VerbJson.logOf(file.readText())!!))
        // A hand's change says so, with its machine: for order and undo, never anything learned.
        assertEquals("hand", again.log.entries.last().origin.actor)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), again.log.entries.map { it.version })
    }

    @Test fun aChangeThatCantApplyChangesNothingAndTheChatStays() {
        val canvas = fresh()
        val before = file.readText()
        // Onto the chat; off the grid; the chat's removal: refused, and neither the page nor the log moves.
        assertFalse(canvas.apply(CanvasVerb.Move("module:needs", DESKTOP_PAGE, Cell(0, 0, 4, 3))).applied)
        assertFalse(canvas.apply(CanvasVerb.Move("module:needs", DESKTOP_PAGE, Cell(10, 0, 4, 3))).applied)
        assertFalse(canvas.apply(CanvasVerb.Remove(CHAT_BLOCK)).applied)
        assertFalse(canvas.add("module:needs").applied)
        assertFalse(canvas.add("module:clock").applied)
        assertEquals(before, file.readText())
        assertTrue(canvas.has(CHAT_BLOCK))
    }

    @Test fun aLogThatCantBeReadIsSetAsideAndOneWithoutTheChatGetsItBack() {
        file.writeText("{not json")
        val canvas = fresh()
        assertNotNull(canvas.setAside)
        assertEquals("{not json", canvas.setAside!!.readText())
        assertTrue(canvas.has(CHAT_BLOCK))
        // A log with only a module in it: the chat is put back in the first room there is.
        val bare = VerbLog(DESKTOP_PAGE).append("r", dev.omalauncher.widgets.layout.VerbOrigin("x", "hand"), CanvasVerb.PageMake(DESKTOP_PAGE, "Soma"))
            .append("r", dev.omalauncher.widgets.layout.VerbOrigin("x", "hand"), CanvasVerb.Place("module:needs", dev.omalauncher.widgets.layout.ItemKind.Module, DESKTOP_PAGE, Cell(0, 0, 4, 3)))
        file.writeText(VerbJson.log(bare))
        val healed = fresh()
        assertEquals(Cell(4, 0, 5, DESKTOP_ROWS), healed.items().first { it.key == CHAT_BLOCK }.cell)
    }

    @Test fun aModuleIsAddedInTheFirstRoomAndSmallerWhenThatIsAllThereIs() {
        val canvas = fresh()
        val offered = addable(canvas, listOf("omalauncher"))
        assertEquals(listOf("module:sessions", "module:pocket", "module:handoff:omalauncher", "module:project:omalauncher"), offered.map { it.first })
        // The page has only a 3x1 free at the bottom: a 4x4 handoff shrinks into it.
        assertTrue(canvas.add(moduleKey(ModuleKind.Handoff, "omalauncher")!!).applied)
        assertTrue(canvas.items().first { it.key == "module:handoff:omalauncher" }.cell!!.let { it.w * it.h in 1..16 })
        assertEquals(4 to 3, desktopSize(ModuleKind.Needs))
        assertEquals(8 to 3, desktopSize(ModuleKind.Timeline))
    }
}
