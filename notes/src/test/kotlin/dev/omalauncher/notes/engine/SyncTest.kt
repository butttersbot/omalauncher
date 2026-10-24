package dev.omalauncher.notes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A Mac in memory, as /v1/notes behaves. */
class FakeMac : Link {
    val files = LinkedHashMap<String, String>()
    var up = true
    private var n = 0
    private val shas = HashMap<String, String>()

    fun sha(id: String) = shas.getOrPut(id + files[id]) { "s${n++}" }

    override fun list(): List<Remote>? =
        if (!up) null else files.keys.map { Remote(it, Wire.title(files[it]!!, it), if (it.startsWith("Agents/")) Wire.AGENT else Wire.MINE, it.substringAfterLast('.'), 1, sha(it)) }

    override fun get(id: String) = files[id]?.let { it to sha(id) }

    override fun put(id: String, text: String, sha: String?): Link.Put {
        if (!up) return Link.Put.Failed
        if (sha == null && id in files) return Link.Put.Exists
        if (sha != null && (id !in files || sha(id) != sha)) return files[id]?.let { Link.Put.Conflict(sha(id), it) } ?: Link.Put.Failed
        files[id] = text
        return Link.Put.Saved(sha(id))
    }

    override fun delete(id: String, sha: String?): Boolean {
        files.remove(id)
        return true
    }
}

class SyncTest {
    private fun shelf(): Shelf = Shelf(Files.createTempDirectory("notes").toFile())

    @Test fun newNoteGoesOutUnderItsTitleAndKeepsWriting() {
        val s = shelf()
        val mac = FakeMac()
        val id = s.fresh(1)
        s.write(id, "Groceries\n- [ ] Milk")
        val r = Sync(s, mac).run()
        assertEquals(1, r.sent)
        assertEquals("Groceries\n- [ ] Milk", mac.files["Groceries.md"])
        // The open page still writes to it, under its new name.
        s.write(id, "Groceries\n- [ ] Milk\n- [ ] Eggs")
        assertEquals("Groceries.md", s.now(id))
        Sync(s, mac).run()
        assertEquals("Groceries\n- [ ] Milk\n- [ ] Eggs", mac.files["Groceries.md"])
        assertEquals(1, s.all().size)
    }

    @Test fun takenNamesGetANumber() {
        val s = shelf()
        val mac = FakeMac().apply { files["Groceries.md"] = "Groceries\nold" }
        s.write(s.fresh(1), "Groceries\nnew")
        Sync(s, mac).run()
        assertEquals("Groceries\nnew", mac.files["Groceries 2.md"])
        assertEquals("Groceries\nold", mac.files["Groceries.md"])
    }

    @Test fun offlineKeepsItForLater() {
        val s = shelf()
        val mac = FakeMac().apply { up = false }
        s.write(s.fresh(1), "Heater clicking")
        val r = Sync(s, mac).run()
        assertFalse(r.reached)
        assertEquals(1, r.left)
        mac.up = true
        assertEquals(0, Sync(s, mac).run().left)
        assertTrue("Heater clicking.md" in mac.files)
    }

    @Test fun agentNotesComeInAndDeletionsGoBothWays() {
        val s = shelf()
        val mac = FakeMac().apply { files["Agents/claude/plan.md"] = "# Plan\nbody"; files["Mine.md"] = "Mine" }
        Sync(s, mac).run()
        assertTrue(s["Agents/claude/plan.md"]!!.agent)
        assertEquals("Plan", s["Agents/claude/plan.md"]!!.title)
        mac.files.remove("Agents/claude/plan.md")
        s.delete("Mine.md")
        Sync(s, mac).run()
        assertTrue(s.all().isEmpty())
        assertTrue(mac.files.isEmpty())
    }

    @Test fun bothSidesEditedAreMerged() {
        val s = shelf()
        val mac = FakeMac().apply { files["List.md"] = "List\n- [ ] Milk\n- [ ] Eggs" }
        Sync(s, mac).run()
        mac.files["List.md"] = "List\n- [ ] Milk\n- [ ] Eggs\n- [ ] Oat milk"
        s.write("List.md", "List\n- [x] Milk\n- [ ] Eggs\n- [ ] Bread")
        Sync(s, mac).run()
        assertEquals("List\n- [x] Milk\n- [ ] Eggs\n- [ ] Bread\n- [ ] Oat milk", mac.files["List.md"])
        assertEquals(mac.files["List.md"], s.text("List.md"))
        assertFalse(s["List.md"]!!.dirty)
    }

    @Test fun emptyPagesNeverGo() {
        val s = shelf()
        val mac = FakeMac()
        s.write(s.fresh(1), "  ")
        Sync(s, mac).run()
        assertTrue(mac.files.isEmpty())
        assertTrue(s.all().isEmpty())
    }

    @Test fun shelfOutlastsTheApp() {
        val dir = Files.createTempDirectory("notes").toFile()
        Shelf(dir).write("A.md", "A\ntext")
        assertEquals("A\ntext", Shelf(dir).text("A.md"))
        assertTrue(Shelf(dir)["A.md"]!!.dirty)
        assertNull(Shelf(File(dir, "other"))["A.md"])
    }

    @Test fun pinsOutlastSyncs() {
        val s = shelf()
        val mac = FakeMac().apply { files["A.md"] = "A" }
        Sync(s, mac).run()
        s.pin("A.md", true)
        mac.files["A.md"] = "A\nmore from the Mac"
        Sync(s, mac).run()
        assertTrue(s["A.md"]!!.pinned)
        assertEquals("A\nmore from the Mac", s.text("A.md"))
    }
}
