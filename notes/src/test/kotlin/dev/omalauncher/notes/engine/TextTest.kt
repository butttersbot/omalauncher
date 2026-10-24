package dev.omalauncher.notes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun titles() {
        assertEquals("Groceries", Wire.title("\n# Groceries\n- milk"))
        assertEquals("Milk", Wire.title("- [ ] Milk"))
        assertEquals("report", Wire.title("", "Agents/x/report.md"))
        assertEquals("Herd weekly", Wire.title("<html><title>Herd weekly</title>", "Agents/r.html"))
    }

    @Test fun fileNames() {
        assertEquals("Plan a b.md", Wire.fileFor("Plan: a/b", emptySet()))
        assertEquals("Note.md", Wire.fileFor("???", emptySet()))
        assertEquals("A 3.md", Wire.fileFor("A", setOf("a.md", "A 2.md")))
    }

    @Test fun safeIds() {
        assertTrue(Wire.safe("Agents/claude/x.md"))
        assertFalse(Wire.safe("../x.md"))
        assertFalse(Wire.safe(".trash/x.md"))
        assertFalse(Wire.safe("/etc/passwd"))
        assertEquals("Agents%2Freport%20one.md", Wire.path("Agents/report one.md"))
    }

    @Test fun listParses() {
        val (rev, notes) = Wire.list("""{"rev":"abc","notes":[{"id":"Agents/c/p.md","title":"P","kind":"agent","ext":"md","modified":5,"size":1,"sha":"s1"},{"id":"../bad","sha":"x"}]}""")!!
        assertEquals("abc", rev)
        assertEquals(1, notes.size)
        assertEquals(Wire.AGENT, notes[0].kind)
    }

    @Test fun mergeKeepsBothSides() {
        assertEquals("a\nb\nc", Merge.merge("a\nb", "a\nb", "a\nb\nc"))
        assertEquals("a\nx\nb\nc", Merge.merge("a\nb", "a\nx\nb", "a\nb\nc"))
        assertEquals("a\nc", Merge.merge("a\nb\nc", "a\nb\nc\n", "a\nc").trimEnd())
    }

    @Test fun markdown() {
        val h = Markdown.html("# T\n\nHi **b** `<x>` [l](https://e.com)\n\n- [ ] one\n- [x] two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\n<script>\n```\n<script>alert(1)</script>")
        assertTrue(h, "<h1>T</h1>" in h)
        assertTrue("<b>b</b>" in h)
        assertTrue("<code>&lt;x&gt;</code>" in h)
        assertTrue("<a href=\"https://e.com\">l</a>" in h)
        assertTrue("data-line=\"4\"" in h)
        assertTrue("task done" in h)
        assertTrue("<td data-h=\"a\">1</td>" in h)
        assertFalse("<script>" in h)
    }

    @Test fun toggleTicks() {
        assertEquals("x\n- [x] a", Markdown.toggle("x\n- [ ] a", 1))
        assertEquals("x\n- [ ] a", Markdown.toggle("x\n- [X] a", 1))
        assertEquals("x", Markdown.toggle("x", 0))
    }

    @Test fun firstLineReadsAsTitle() {
        assertEquals("\n# Groceries\n- [ ] Milk", ReaderPage.titled("\nGroceries\n- [ ] Milk"))
        assertEquals("# Plan\nx", ReaderPage.titled("# Plan\nx"))
        assertEquals("- [ ] Milk", ReaderPage.titled("- [ ] Milk"))
    }

    @Test fun mineIsOneListPinnedFirst() {
        val now = 1_800_000_000_000L
        val got = dev.omalauncher.notes.ui.sections(listOf(Note("a.md", "A", Wire.MINE, "md", now, "s"), Note("b.md", "B", Wire.MINE, "md", now - 30L * 86_400_000, "s", pinned = true)), agents = false)
        assertEquals(1, got.size)
        assertEquals(null, got[0].first)
        assertEquals(listOf("b.md", "a.md"), got[0].second.map { it.id })
    }

    @Test fun agentNotesAreGroupedByProject() {
        val got =
            dev.omalauncher.notes.ui.sections(
                listOf(
                    Note("Agents/soma-notes/Banked ideas.md", "B", Wire.AGENT, "md", 30, "s"),
                    Note("Agents/tailshare/Plan.md", "P", Wire.AGENT, "md", 20, "s"),
                    Note("Agents/soma-notes/Old.md", "O", Wire.AGENT, "md", 10, "s"),
                    Note("Agents/loose.md", "L", Wire.AGENT, "md", 5, "s"),
                ),
                agents = true,
            )
        assertEquals(listOf("Soma Notes", "Tailshare", "Other"), got.map { it.first })
        assertEquals(2, got[0].second.size)
    }

    @Test fun searchFindsTitlesFirstThenText() {
        val notes =
            listOf(
                Note("Groceries.md", "Groceries", Wire.MINE, "md", 10, "s"),
                Note("Agents/omalauncher/Current state.md", "Current state", Wire.AGENT, "md", 20, "s", readonly = true),
                Note("Old.md", "Old", Wire.MINE, "md", 5, "s", gone = true),
            )
        val texts = mapOf("Groceries.md" to "Groceries\n- [ ] Milk", "Agents/omalauncher/Current state.md" to "# Now\nSoma Notes groceries list is next", "Old.md" to "groceries")
        val hits = Search.find(notes, { texts[it].orEmpty() }, "groceries")
        assertEquals(listOf("Groceries.md", "Agents/omalauncher/Current state.md"), hits.map { it.id })
        assertEquals("omalauncher", hits[1].project)
        assertTrue(hits[1].snippet, "groceries" in hits[1].snippet.lowercase())
        assertEquals(listOf("Groceries.md"), Search.find(notes, { texts[it].orEmpty() }, "groc", titlesOnly = true).map { it.id })
        assertTrue(Search.find(notes, { texts[it].orEmpty() }, "milk next").isEmpty())
    }

    @Test fun readOnlyNotesAreNeverWritten() {
        val s = Shelf(java.nio.file.Files.createTempDirectory("notes").toFile())
        s.took(Remote("Agents/x/Brief.md", "Brief", Wire.AGENT, "md", 1, "a", readonly = true), "# Project brief")
        assertEquals("Brief", s["Agents/x/Brief.md"]!!.title)
        s.write("Agents/x/Brief.md", "changed")
        s.delete("Agents/x/Brief.md")
        assertEquals("# Project brief", s.text("Agents/x/Brief.md"))
        assertFalse(s["Agents/x/Brief.md"]!!.dirty || s["Agents/x/Brief.md"]!!.gone)
    }
}
