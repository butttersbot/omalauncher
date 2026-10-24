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
}
