package dev.omalauncher.widgets.annotations

import dev.omalauncher.widgets.LiveNotebook
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookPage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-page annotations files and the book over them: what is written, when, and what is never written over. */
class AnnotationStoreTest {
    private val dir = Files.createTempDirectory("annotations").toFile()
    private val files = AnnotationFiles(dir)

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun stroke(vararg points: Float) = Stroke(InkTool.Pen, InkRole.Pen, 0.0065f, points)

    private val sample =
        Annotations(
            strokes =
                listOf(
                    stroke(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f),
                    Stroke(InkTool.Highlighter, InkRole.Highlighter, 0.032f, floatArrayOf(0f, 0f, 1f, 1f)),
                    Stroke(InkTool.Pen, InkRole.Second, 0.0065f, floatArrayOf(0.5f, 0.5f)),
                ),
            notes =
                listOf(
                    StickyNote("n1", 0.06f, 0.06f, 0.44f, 0.16f, "milk\neggs \"quoted\" \\ 日本語 ☕", NoteRole.Sticky, 1, Author.Owner, 1_700_000_000_000),
                    StickyNote("n2", 0.1f, 0.11f, 0.44f, 0.16f, "Booked the table.", NoteRole.Alt, 2, Author.Agent, 1_700_000_000_500),
                ),
            stickers = listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f, 345), Sticker("s2", StickerKind.Smile, 0.2f, 0.9f, 0.3f)),
        )

    // The document.

    @Test fun whatIsWrittenReadsBackTheSame() {
        val text = AnnotationStore.write(sample)
        assertEquals(AnnotationsRead.Read(sample), AnnotationStore.read(text))
        // And again: nothing drifts from one save to the next.
        assertEquals(text, AnnotationStore.write((AnnotationStore.read(text) as AnnotationsRead.Read).annotations))
        assertEquals(AnnotationsRead.Read(Annotations()), AnnotationStore.read(AnnotationStore.write(Annotations())))
        // In the wrapper every saved document has, at this version.
        val root = JSONObject(text)
        assertEquals(1, root.getInt("v"))
        assertEquals(AnnotationStore.VERSION, root.getInt("v"))
        assertEquals(3, root.getJSONObject("data").getJSONArray("strokes").length())
    }

    @Test fun nothingAtAllIsAPageNobodyWroteOn() {
        assertEquals(AnnotationsRead.None, AnnotationStore.read(null))
        assertEquals(AnnotationsRead.None, AnnotationStore.read(""))
        assertEquals(AnnotationsRead.None, AnnotationStore.read("  \n"))
        assertEquals(AnnotationsRead.Read(Annotations()), AnnotationStore.read("""{"v":1,"data":{}}"""))
    }

    private val damaged =
        listOf(
            "not json",
            "[1,2,3]",
            "{",
            """{"v":1}""",
            """{"data":{}}""",
            """{"v":0,"data":{}}""",
            """{"v":"one","data":{}}""",
            """{"v":1,"data":[]}""",
            """{"v":1,"data":{"strokes":{}}}""",
            // One stroke of two is no stroke: the document isn't read in part.
            """{"v":1,"data":{"strokes":[{"t":"pen","c":"pen","w":65,"p":[1,2]},{"t":"brush","c":"pen","w":65,"p":[1,2]}]}}""",
            """{"v":1,"data":{"strokes":[{"t":"pen","c":"pen","w":65,"p":[1,2,3]}]}}""",
            """{"v":1,"data":{"strokes":[{"t":"pen","c":"pen","w":65,"p":[]}]}}""",
            """{"v":1,"data":{"strokes":["x"]}}""",
            """{"v":1,"data":{"notes":[{"id":"n1","x":1,"y":1,"w":1,"h":1,"c":"sticky","z":1,"by":"me","at":1}]}}""",
            """{"v":1,"data":{"notes":[{"id":"n1","x":1,"y":1,"w":1,"h":1,"text":"t","c":"sticky","z":1,"by":"someone","at":1}]}}""",
            """{"v":1,"data":{"stickers":[{"id":"s1","k":"unicorn","x":1,"y":1,"s":1,"r":0}]}}""",
            // Two things with one id.
            """{"v":1,"data":{"stickers":[{"id":"a","k":"star","x":1,"y":1,"s":1000,"r":0},{"id":"a","k":"star","x":1,"y":1,"s":1000,"r":0}]}}""",
            "[".repeat(100_000),
        )

    @Test fun anythingThatDoesNotParseIsUnreadableAsAWhole() {
        for (text in damaged) assertEquals(text.take(60), AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged), AnnotationStore.read(text))
    }

    @Test fun aNewerLaunchersDocumentIsNotThisOnesToRead() {
        val newer = AnnotationStore.write(sample).replace("\"v\":1", "\"v\":2")
        assertEquals(AnnotationsRead.Unreadable(AnnotationsRead.Why.Newer), AnnotationStore.read(newer))
        assertEquals(AnnotationsRead.Unreadable(AnnotationsRead.Why.Newer), AnnotationStore.read("""{"v":99,"data":"whatever comes later"}"""))
    }

    @Test fun whatIsReadIsOnThePageWhateverTheFileSays() {
        val wild =
            """{"v":1,"data":{"strokes":[{"t":"pen","c":"pen","w":999999,"p":[-500,20000,5000,5000]}],
               "notes":[{"id":"n","x":99999,"y":-5,"w":0,"h":0,"text":"t","c":"alt","z":-3,"by":"oma","at":0}],
               "stickers":[{"id":"s","k":"bolt","x":-1,"y":70000,"s":0,"r":-90}]}}"""
        val read = (AnnotationStore.read(wild) as AnnotationsRead.Read).annotations
        assertEquals(listOf(0f, 1f, 0.5f, 0.5f), read.strokes.single().points.toList())
        assertEquals(AnnotationLimits.MAX_WIDTH, read.strokes.single().width, 0f)
        val note = read.notes.single()
        assertTrue(note.x + note.w <= 1f && note.y >= 0f && note.w >= AnnotationLimits.MIN_NOTE)
        val sticker = read.stickers.single()
        assertEquals(listOf(0f, 1f, AnnotationLimits.MIN_STICKER), listOf(sticker.x, sticker.y, sticker.size))
        assertEquals(270, sticker.turn)
    }

    @Test fun aPageAtEveryBoundStillFitsInAFileThatIsRead() {
        // The most a page can hold: every stroke at its most points, each number at its longest.
        val strokes = (0 until AnnotationLimits.STROKES).map { Stroke(InkTool.Highlighter, InkRole.Highlighter, 0.1f, FloatArray(2 * AnnotationLimits.POINTS) { 1f }) }
        val notes = (0 until AnnotationLimits.NOTES).map { StickyNote("note-$it", 0.1f, 0.1f, 0.5f, 0.5f, " ".repeat(AnnotationLimits.NOTE_TEXT), z = it, at = Long.MAX_VALUE) }
        val stickers = (0 until AnnotationLimits.STICKERS).map { Sticker("sticker-$it", StickerKind.Flower, 0.5f, 0.5f, 0.5f, 345) }
        val full = Annotations(strokes, notes, stickers)
        files.write("p1", full)
        assertTrue(files.file("p1").length() < AnnotationLimits.FILE_BYTES)
        assertEquals(AnnotationsRead.Read(full), files.read("p1"))
    }

    // The files.

    @Test fun aPagesFileIsReplacedInOneStepOrNotAtAll() {
        files.write("p1", sample)
        assertEquals(AnnotationsRead.Read(sample), files.read("p1"))
        val second = sample.copy(notes = emptyList())
        files.write("p1", second)
        assertEquals(AnnotationsRead.Read(second), files.read("p1"))
        // Only the page's file is left: what it was written through is gone.
        assertEquals(listOf("p1.json"), dir.list()!!.toList())
        // A write that can't finish leaves the file as it was: here the temporary file can't even be made.
        val blocked = File(dir, ".p1.json.tmp").apply { mkdir() }
        assertThrows(Exception::class.java) { files.write("p1", sample) }
        assertEquals(AnnotationsRead.Read(second), files.read("p1"))
        blocked.delete()
        // Other pages have their own files.
        files.write("p2", sample)
        assertEquals(AnnotationsRead.Read(second), files.read("p1"))
        assertEquals(setOf("p1", "p2"), files.pages())
    }

    @Test fun aFileThatCannotBeUsedSaysSoAndNoFileIsNone() {
        assertEquals(AnnotationsRead.None, files.read("p1"))
        dir.mkdirs()
        File(dir, "p1.json").writeText("{ cut sho")
        assertEquals(AnnotationsRead.Unreadable(AnnotationsRead.Why.Damaged), files.read("p1"))
        File(dir, "p2.json").writeBytes(ByteArray(AnnotationLimits.FILE_BYTES.toInt() + 1) { ' '.code.toByte() })
        assertEquals(AnnotationsRead.Unreadable(AnnotationsRead.Why.TooBig), files.read("p2"))
        File(dir, "p3.json").mkdir()
        assertEquals(AnnotationsRead.Unreadable(AnnotationsRead.Why.Failed), files.read("p3"))
        File(dir, "p4.json").writeText("")
        assertEquals(AnnotationsRead.None, files.read("p4"))
    }

    @Test fun onlyAPageIdNamesAFile() {
        for (id in listOf("p1", "p12", "page_2-b", "A")) assertTrue(id, files.valid(id))
        for (id in listOf("", ".", "..", "../p1", "a/b", "a\\b", "p1.json", "p 1", "p\u0000", "x".repeat(65), ".removed")) {
            assertFalse(id, files.valid(id))
            assertThrows(id, IllegalArgumentException::class.java) { files.file(id) }
        }
        assertEquals(File(dir, "p3.json"), files.file("p3"))
    }

    @Test fun aDeletedPagesFileIsSetAsideNotDestroyed() {
        files.write("p2", sample)
        assertTrue(files.remove("p2", 77))
        assertFalse(files.file("p2").exists())
        assertEquals(AnnotationsRead.None, files.read("p2"))
        assertEquals(emptySet<String>(), files.pages())
        val kept = File(dir, ".removed/p2-77.json")
        assertEquals(AnnotationsRead.Read(sample), AnnotationStore.read(kept.readText()))
        // Twice at the same moment: neither copy is written over.
        files.write("p2", Annotations())
        assertTrue(files.remove("p2", 77))
        assertEquals(AnnotationsRead.Read(sample), AnnotationStore.read(kept.readText()))
        assertTrue(File(dir, ".removed/p2-77-2.json").isFile)
        // Nothing to remove is no failure.
        assertTrue(files.remove("p9", 1))
    }

    // The book.

    /** A book whose off-thread work and waits run when the test says. */
    private inner class Bench(
        files: AnnotationFiles = this@AnnotationStoreTest.files,
    ) {
        val deferred = LinkedHashMap<String, () -> Unit>()
        var time = 1_000L
        val book =
            AnnotationBook(
                files = files,
                background = { it() },
                foreground = { it() },
                defer = { page, save -> if (save == null) deferred.remove(page) else deferred[page] = save },
                now = { time++ },
            )

        /** The moment after the last change has passed. */
        fun later() {
            val due = deferred.values.toList()
            deferred.clear()
            due.forEach { it() }
        }

        fun opened(vararg pages: String): AnnotationBook {
            book.keep(pages.toSet())
            pages.forEach(book::open)
            return book
        }
    }

    private fun onDisk(page: String) = (files.read(page) as? AnnotationsRead.Read)?.annotations

    @Test fun aChangeIsWrittenAMomentLaterAndOnlyOnce() {
        val bench = Bench()
        val book = bench.opened("p1")
        assertEquals(AnnotationBook.Status.Ready, book.status("p1"))
        val a = stroke(0.1f, 0.1f, 0.2f, 0.2f)
        val b = stroke(0.3f, 0.3f, 0.4f, 0.4f)
        assertTrue(book.draw("p1", a))
        assertTrue(book.draw("p1", b))
        // Shown at once, written not yet: a hand that is still writing doesn't write the file with every stroke.
        assertEquals(listOf(a, b), book.on("p1").strokes)
        assertFalse(files.file("p1").exists())
        assertEquals(1, bench.deferred.size)
        bench.later()
        assertEquals(listOf(a, b), onDisk("p1")!!.strokes)
        // Nothing more to write until something changes.
        val written = files.file("p1").lastModified()
        files.file("p1").setLastModified(1_000)
        bench.later()
        book.flush()
        assertEquals(1_000, files.file("p1").lastModified())
        assertTrue(written > 0)
    }

    @Test fun leavingHomeWritesAtOnce() {
        val bench = Bench()
        val book = bench.opened("p1", "p2")
        book.draw("p1", stroke(0.1f, 0.1f))
        val note = book.addNote("p2")!!
        book.flush()
        assertEquals(1, onDisk("p1")!!.strokes.size)
        assertEquals(note, onDisk("p2")!!.notes.single().id)
        assertTrue(bench.deferred.isEmpty())
    }

    @Test fun aNextStartShowsWhatTheLastOneWrote() {
        val first = Bench()
        val book = first.opened("p1")
        book.draw("p1", stroke(0.1f, 0.1f, 0.5f, 0.5f))
        val note = book.addNote("p1")!!
        book.changeNote("p1", note) { it.copy(text = "milk", x = 0.3f, y = 0.4f) }
        val sticker = book.addSticker("p1", StickerKind.Heart)!!
        book.changeSticker("p1", sticker) { it.copy(turn = it.turn + TURN_STEP, size = 0.2f) }
        first.later()
        val again = Bench().opened("p1")
        assertEquals(book.on("p1"), again.on("p1"))
        assertEquals("milk", again.on("p1").notes.single().text)
        assertEquals(0.3f, again.on("p1").notes.single().x, 0f)
        assertEquals(TURN_STEP, again.on("p1").stickers.single().turn)
        // Undo is the session's: what was loaded isn't taken back.
        assertFalse(again.ink("p1").canUndo)
    }

    @Test fun aFileThatCannotBeReadIsNeverWrittenOver() {
        for (text in listOf("{ cut sho", AnnotationStore.write(sample).replace("\"v\":1", "\"v\":2"))) {
            dir.mkdirs()
            val file = File(dir, "p1.json").apply { writeText(text) }
            val bench = Bench()
            val book = bench.opened("p1", "p2")
            assertEquals(AnnotationBook.Status.Unreadable, book.status("p1"))
            // He is told, once, in a sentence.
            assertTrue(book.notice == AnnotationBook.UNREADABLE || book.notice == AnnotationBook.NEWER)
            // Nothing of it shows, and nothing is taken.
            assertTrue(book.on("p1").isEmpty)
            assertFalse(book.draw("p1", stroke(0.1f, 0.1f)))
            assertNull(book.addNote("p1"))
            assertNull(book.addSticker("p1", StickerKind.Star))
            assertFalse(book.undo("p1"))
            assertFalse(book.erase("p1", Ink(), listOf(0)))
            assertTrue(book.leaveNote("p1", "did it") is NoteLeft.Refused)
            assertFalse(book.takes("p1"))
            bench.later()
            book.flush()
            assertEquals(text, file.readText())
            // The other pages are none the worse.
            assertTrue(book.draw("p2", stroke(0.1f, 0.1f)))
            bench.later()
            assertEquals(text, file.readText())
            assertEquals(1, onDisk("p2")!!.strokes.size)
            assertEquals(listOf("p1.json", "p2.json"), dir.list()!!.sorted())
            file.delete()
            File(dir, "p2.json").delete()
        }
    }

    @Test fun aFileThatFailsToReadIsNotTakenForAnEmptyPage() {
        // What is under the page's name can't be read as a file: not known to be empty, so not written over.
        dir.mkdirs()
        File(dir, "p1.json").mkdir()
        val bench = Bench()
        val book = bench.opened("p1")
        assertEquals(AnnotationBook.Status.Unreadable, book.status("p1"))
        assertFalse(book.draw("p1", stroke(0.1f, 0.1f)))
        bench.later()
        assertTrue(File(dir, "p1.json").isDirectory)
    }

    @Test fun nothingIsChangedOrWrittenUntilTheNotebookIsKept() {
        files.write("p1", sample)
        val bench = Bench()
        val book = bench.book
        book.open("p1")
        // What is saved shows; a session whose layout isn't saved doesn't change it.
        assertEquals(sample, book.on("p1"))
        assertFalse(book.writable)
        assertFalse(book.draw("p1", stroke(0.1f, 0.1f)))
        assertNull(book.addNote("p1"))
        assertEquals(AnnotationBook.NOT_KEPT, book.notice)
        assertTrue(book.leaveNote("p1", "did it") is NoteLeft.Refused)
        // A page deleted in such a session is back at the next start, and its file was never touched.
        book.pageGone("p1")
        bench.later()
        assertEquals(sample, onDisk("p1"))
        // Once it is kept, changes are taken.
        book.keep(setOf("p1"))
        book.open("p1")
        assertTrue(book.draw("p1", stroke(0.1f, 0.1f)))
    }

    @Test fun thePageSaysItIsFullRatherThanDropOldInk() {
        val old = (0 until AnnotationLimits.STROKES).map { stroke(unit(it / 4000f), 0.5f) }
        files.write("p1", Annotations(strokes = old))
        val bench = Bench()
        val book = bench.opened("p1")
        assertFalse(book.roomToDraw("p1"))
        assertEquals(AnnotationBook.FULL, book.notice)
        book.notice = null
        assertFalse(book.draw("p1", stroke(0.9f, 0.9f)))
        assertEquals(AnnotationBook.FULL, book.notice)
        assertEquals(old, book.on("p1").strokes)
        bench.later()
        assertEquals(old, onDisk("p1")!!.strokes)
        // Erasing makes room.
        assertTrue(book.erase("p1", book.ink("p1"), listOf(0, 1)))
        assertTrue(book.roomToDraw("p1"))
        assertTrue(book.draw("p1", stroke(0.9f, 0.9f)))
        assertEquals(AnnotationLimits.STROKES - 1, book.on("p1").strokes.size)
    }

    @Test fun onePassOfTheEraserIsOneStepToUndo() {
        val bench = Bench()
        val book = bench.opened("p1")
        val strokes = (0 until 5).map { stroke(it / 10f, 0.5f) }
        strokes.forEach { book.draw("p1", it) }
        // The eraser comes down, and touches more and more as it moves.
        val from = book.ink("p1")
        book.erase("p1", from, listOf(1))
        book.erase("p1", from, listOf(1, 3))
        book.erase("p1", from, listOf(1, 3, 4))
        assertEquals(listOf(strokes[0], strokes[2]), book.on("p1").strokes)
        assertTrue(book.undo("p1"))
        assertEquals(strokes, book.on("p1").strokes)
        assertTrue(book.redo("p1"))
        assertEquals(listOf(strokes[0], strokes[2]), book.on("p1").strokes)
        assertFalse(book.redo("p1"))
        bench.later()
        assertEquals(listOf(strokes[0], strokes[2]), onDisk("p1")!!.strokes)
    }

    @Test fun notesAndStickersAreBoundedPerPage() {
        val bench = Bench()
        val book = bench.opened("p1")
        val notes = (0 until AnnotationLimits.NOTES).map { book.addNote("p1")!! }
        assertEquals(notes.size, notes.toSet().size)
        assertNull(book.addNote("p1"))
        assertEquals(AnnotationBook.NO_ROOM_NOTE, book.notice)
        val stickers = (0 until AnnotationLimits.STICKERS).map { book.addSticker("p1", StickerKind.entries[it % StickerKind.entries.size])!! }
        assertNull(book.addSticker("p1", StickerKind.Star))
        assertEquals(AnnotationBook.NO_ROOM_STICKER, book.notice)
        // A note takes no more text than its bound, however it is given.
        book.changeNote("p1", notes[0]) { it.copy(text = "x".repeat(9_000)) }
        assertEquals(AnnotationLimits.NOTE_TEXT, book.on("p1").notes.first { it.id == notes[0] }.text.length)
        // Deleting makes room again.
        assertTrue(book.remove("p1", notes[1]))
        assertTrue(book.remove("p1", stickers[1]))
        assertFalse(book.remove("p1", "nothing"))
        assertTrue(book.addNote("p1") != null)
        assertTrue(book.addSticker("p1", StickerKind.Bolt) != null)
    }

    @Test fun whatTheOwnerTouchesOfAnAgentsNoteIsHis() {
        val bench = Bench()
        val book = bench.opened("p1")
        val first = (book.leaveNote("p1", "did 1") as NoteLeft.Left).note
        // Moved by the owner: his from now on, and on top.
        assertTrue(book.changeNote("p1", first.id, raise = true) { it.copy(x = 0.4f) })
        assertEquals(Author.Owner, book.on("p1").notes.single().author)
        repeat(AnnotationLimits.AGENT_NOTES + 3) { book.leaveNote("p1", "later $it") }
        val notes = book.on("p1").notes
        assertTrue(notes.any { it.id == first.id && it.text == "did 1" })
        assertEquals(AnnotationLimits.AGENT_NOTES, notes.count { it.author == Author.Agent })
        assertEquals(listOf("later 3", "later 4", "later 5", "later 6"), notes.filter { it.author == Author.Agent }.map { it.text })
        // A change that changes nothing makes nothing his.
        val agents = notes.first { it.author == Author.Agent }
        assertFalse(book.changeNote("p1", agents.id) { it })
        assertEquals(Author.Agent, book.on("p1").notes.first { it.id == agents.id }.author)
        bench.later()
        assertEquals(book.on("p1"), onDisk("p1"))
    }

    @Test fun anAgentsNoteWaitsForThePageToBeRead() {
        files.write("p3", sample)
        val waiting = ArrayList<() -> Unit>()
        val book = AnnotationBook(files, background = { waiting += it }, foreground = { it() }, defer = { _, save -> save?.invoke() }, now = { 5 })
        book.keep(setOf("p3"))
        var answered: Boolean? = null
        book.whenRead("p3") { answered = it }
        assertNull(answered)
        assertTrue(book.leaveNote("p3", "too soon") is NoteLeft.Refused)
        waiting.toList().also { waiting.clear() }.forEach { it() }
        assertEquals(true, answered)
        val left = book.leaveNote("p3", "did it") as NoteLeft.Left
        // On top of what was there, none of which is gone.
        assertEquals(sample.notes + left.note, book.on("p3").notes)
        assertEquals(sample.strokes, book.on("p3").strokes)
        // Asked again for a page already read, the answer is at once.
        var again: Boolean? = null
        book.whenRead("p3") { again = it }
        assertEquals(true, again)
    }

    // Pages renamed, reordered and deleted.

    private fun notebook(book: AnnotationBook) =
        LiveNotebook().also {
            it.open(Notebook(rows = 10, pages = listOf(NotebookPage("p1", "Today"), NotebookPage("p2", "Trip"), NotebookPage("p3", "Work"))), kept = true)
            it.onPageGone = book::pageGone
        }

    @Test fun renamingAndReorderingAPageKeepWhatIsOnIt() {
        val bench = Bench()
        val book = bench.opened("p1", "p2", "p3")
        val live = notebook(book)
        book.draw("p2", stroke(0.1f, 0.1f, 0.9f, 0.9f))
        val note = book.addNote("p2")!!
        bench.later()
        val before = book.on("p2")
        assertTrue(live.renamePage("p2", "Lisbon"))
        assertTrue(live.movePage("p2", -1))
        assertTrue(live.movePage("p3", -1))
        assertEquals(listOf("p2", "p3", "p1"), live.pages.map { it.id })
        // The page's id is what its file goes by, and that never changed.
        assertEquals(before, book.on("p2"))
        assertEquals(before, onDisk("p2"))
        assertEquals(note, book.on("p2").notes.single().id)
        // And the next start finds it under the same page.
        assertEquals(before, Bench().opened("p2").on("p2"))
        assertFalse(File(dir, AnnotationFiles.REMOVED).exists())
    }

    @Test fun deletingAPageTakesItsFileAndOnlyItsFile() {
        val bench = Bench()
        val book = bench.opened("p1", "p2", "p3")
        val live = notebook(book)
        for (page in listOf("p1", "p2", "p3")) book.draw(page, stroke(0.1f, 0.1f))
        bench.later()
        // A change still waiting to be written when the page goes is not written after it.
        book.draw("p2", stroke(0.5f, 0.5f))
        assertTrue(live.deletePage("p2", emptySet(), move = true) != null)
        bench.later()
        book.flush()
        assertFalse(files.file("p2").exists())
        assertEquals(setOf("p1", "p3"), files.pages())
        assertTrue(book.on("p2").isEmpty)
        assertNull(book.status("p2"))
        // Set aside, not destroyed.
        assertEquals(1, File(dir, AnnotationFiles.REMOVED).list()!!.size)
        assertEquals(1, onDisk("p1")!!.strokes.size)
        assertEquals(1, onDisk("p3")!!.strokes.size)
    }

    @Test fun aNewPageGivenADeletedPagesIdStartsClean() {
        val bench = Bench()
        val book = bench.opened("p1", "p2", "p3")
        val live = notebook(book)
        book.draw("p3", stroke(0.1f, 0.1f))
        bench.later()
        live.deletePage("p3", emptySet(), move = true)
        // The notebook hands the highest free number out again.
        assertEquals("p3", live.addPage())
        book.open("p3")
        assertEquals(AnnotationBook.Status.Ready, book.status("p3"))
        assertTrue(book.on("p3").isEmpty)
        assertFalse(book.ink("p3").canUndo)
        // An offered page that was taken back goes the same way.
        val offered = live.addPage()!!
        book.open(offered)
        files.write(offered, sample)
        assertTrue(live.withdrawPage(offered))
        assertFalse(files.file(offered).exists())
    }

    @Test fun anOldPagesLeftoverFileNeverBecomesANewPagesInk() {
        // Left behind for an id no page has: the launcher died between deleting the page and its file.
        files.write("p4", sample)
        files.write("p1", sample)
        val bench = Bench()
        val book = bench.opened("p1")
        // Nothing is touched for as long as no page takes the id.
        assertEquals(sample, onDisk("p4"))
        assertEquals(sample, book.on("p1"))
        // A new page is given it: the leftover is set aside first, and the page starts with nothing.
        book.open("p4")
        assertEquals(AnnotationBook.Status.Ready, book.status("p4"))
        assertTrue(book.on("p4").isEmpty)
        assertEquals(1, File(dir, AnnotationFiles.REMOVED).list()!!.size)
        // A page that was there at the start is never taken for a leftover, however late it is opened.
        files.write("p2", sample)
        val next = Bench()
        next.book.keep(setOf("p1", "p2"))
        next.book.open("p2")
        assertEquals(sample, next.book.on("p2"))
    }

    @Test fun aPageWhoseIdIsNoFileNameHasNoAnnotations() {
        val bench = Bench()
        val book = bench.opened("../evil", "p1")
        assertEquals(AnnotationBook.Status.Unreadable, book.status("../evil"))
        assertFalse(book.draw("../evil", stroke(0.1f, 0.1f)))
        book.pageGone("../evil")
        bench.later()
        assertEquals(emptyList<String>(), dir.parentFile!!.list()!!.filter { it.startsWith("evil") })
    }

    @Test fun aWriteThatFailsIsTriedAgainWithTheNextChange() {
        val bench = Bench()
        val book = bench.opened("p1")
        val blocked = File(dir, ".p1.json.tmp").apply {
            dir.mkdirs()
            mkdir()
        }
        book.draw("p1", stroke(0.1f, 0.1f))
        bench.later()
        assertFalse(files.file("p1").exists())
        assertEquals(AnnotationBook.NOT_SAVED, book.notice)
        blocked.delete()
        // Still on the page, and written when home is left.
        assertEquals(1, book.on("p1").strokes.size)
        book.flush()
        assertEquals(1, onDisk("p1")!!.strokes.size)
    }
}
