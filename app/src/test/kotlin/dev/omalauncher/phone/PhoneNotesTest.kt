package dev.omalauncher.phone

import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.NOTE_OVERLAPS
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.PageTile
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickyNote
import dev.omalauncher.widgets.annotations.box
import dev.omalauncher.widgets.annotations.AnnotationFiles
import dev.omalauncher.widgets.annotations.AnnotationLimits
import dev.omalauncher.widgets.annotations.AnnotationStore
import dev.omalauncher.widgets.annotations.AnnotationsRead
import dev.omalauncher.widgets.annotations.Author
import dev.omalauncher.widgets.annotations.InkRole
import dev.omalauncher.widgets.annotations.InkTool
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.PAGE_TALL
import dev.omalauncher.widgets.annotations.PICTURE_KIND
import dev.omalauncher.widgets.annotations.Picture
import dev.omalauncher.widgets.annotations.snapshotName
import dev.omalauncher.widgets.annotations.StickerKind
import dev.omalauncher.widgets.annotations.Stroke
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The agent's `note` command (PRD AC-29): the gates it is behind, which page it writes on, and how little it can do there. */
class PhoneNotesTest {
    // The gates.

    /** What [PhoneRequests.admit] consulted, in order, and what came of it. */
    private fun admitted(
        access: Boolean = true,
        pending: Int = 0,
    ): Pair<List<String>, Result<PhoneRequests.Route>> {
        val asked = ArrayList<String>()
        val result =
            runCatching {
                PhoneRequests.admit("note", {
                    asked += "access"
                    check(access) { "no access" }
                }) {
                    asked += "pending"
                    pending
                }
            }
        return asked to result
    }

    @Test fun aNoteNeedsAccessLikeEveryOtherCommand() {
        // Paused, locked, disconnected or asleep: refused before anything else is looked at, pending or not.
        for (pending in listOf(0, 1)) {
            val (asked, result) = admitted(access = false, pending = pending)
            assertEquals(listOf("access"), asked)
            assertEquals("no access", result.exceptionOrNull()?.message)
        }
        // The real check refuses each of those states.
        for (state in listOf(listOf(false, false, false, true), listOf(true, true, false, true), listOf(true, false, true, true), listOf(true, false, false, false))) {
            assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireAllowed(state[0], state[1], state[2], state[3]) }
        }
    }

    @Test fun aNoteIsRefusedWhileAnApprovalIsPending() {
        for (pending in listOf(1, 2, 16)) {
            val (asked, result) = admitted(pending = pending)
            assertEquals(listOf("access", "pending"), asked)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("approval is pending"))
        }
        // It writes on home: it is not one of the commands that only look.
        assertFalse(PhoneNotes.COMMAND in PhoneControlPolicy.whilePending)
        assertEquals(setOf("read", "screen", "apps", "screenshot"), PhoneControlPolicy.whilePending)
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.requireNoPendingApproval(1, "note") }
    }

    @Test fun anAdmittedNoteGoesToItsOwnHandlerAndNowhereElse() {
        val (asked, result) = admitted()
        assertEquals(listOf("access", "pending"), asked)
        assertEquals(PhoneRequests.Route.Note, result.getOrThrow())
        assertEquals("note", PhoneNotes.COMMAND)
        // It is not a page command, and nothing that merely looks like it is a note.
        assertFalse("note" in PhonePages.commands)
        for (other in listOf("notes", "Note", " note", "note ", "leave-note")) {
            assertEquals(other, PhoneRequests.Route.Control, PhoneRequests.admit(other, {}) { 0 })
        }
        // The service hands it on by the route alone, after the one admit every request passes, and has no check of its own.
        val service = moduleFile("src/main/kotlin/dev/omalauncher/phone/PhoneService.kt").readText()
        val request = service.substringAfter("fun request(input: JSONObject")
        val handled = request.indexOf("if (route == PhoneRequests.Route.Note)")
        assertTrue(request.indexOf("PhoneRequests.admit(") in 0 until handled)
        assertEquals(1, request.split("PhoneRequests.Route.Note").size - 1)
        assertEquals(1, request.split("PhoneNotes.reply(").size - 1)
        assertFalse(request.contains("\"note\""))
        // Two pads reach the pages' annotations from the service. The note pad writes through leaveNote alone, and the
        // board only reads. The sticker pad (Boss, 2026-10-12) writes stickers and nothing else: it sticks one, changes
        // one or takes one off (after looking that it is a sticker), and has no way to a note, to ink or to a tile.
        // The canvas pad (Soma Canvas slice 6) only asks whether a page has marks, so an agent never takes such a page away.
        assertEquals(3, service.split("HomeAnnotations.of(").size - 1)
        assertTrue(service.substringAfter("marked = { page ->").substringBefore("},").let { "whenRead(page) {}" in it && ".on(page).isEmpty" in it && "leave" !in it && "stick" !in it && "remove" !in it })
        assertEquals(1, service.split("leaveNote(").size - 1)
        val stickers = service.substringAfter("private fun stickerPad()").substringBefore("private fun pagePad()")
        assertEquals(listOf("book.stick(", "book.changeSticker(", "book.on(", "book.remove("), Regex("""\bbook\.\w+\(""").findAll(stickers).map { it.value }.toList())
    }

    // Which page, and what the command answers.

    /** Home as the command sees it, recording what it was asked to leave and where. */
    private class Pad(
        override val asked: String? = null,
        override val showing: String? = "p1",
        val names: Map<String, String> = mapOf("p1" to "Today", "p2" to "Trip"),
        val seen: (String) -> Pair<PageBoard, Annotations?> = { PageBoard() to Annotations() },
        val answer: (String, String, Pair<Int, Int>?) -> NoteLeft = { page, text, _ -> throw AssertionError("left $text on $page") },
    ) : NotePad {
        val left = ArrayList<Pair<String, String>>()
        val looked = ArrayList<String>()

        override val pages get() = names.keys.toList()

        override fun name(page: String) = names[page]

        override fun leave(
            page: String,
            text: String,
            near: Pair<Int, Int>?,
            then: (NoteLeft) -> Unit,
        ) {
            left += page to text
            then(answer(page, text, near))
        }

        override fun look(
            page: String,
            then: (PageBoard, Annotations?) -> Unit,
        ) {
            looked += page
            seen(page).let { (board, marks) -> then(board, marks) }
        }
    }

    /** The owner's first page as he has it: five widgets that leave three cells free, bottom right. */
    private val owners =
        PageBoard(
            9,
            listOf(
                PageTile("today.owl", Cell(0, 0, 3, 3)),
                PageTile("fetch.owl", Cell(3, 0, 3, 3)),
                PageTile("koi.owl", Cell(0, 3, 6, 3)),
                PageTile("owl.owl", Cell(0, 6, 3, 3)),
                PageTile("oma.owl", Cell(3, 6, 3, 2)),
            ),
        )

    private val dir = Files.createTempDirectory("notes").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A book whose pages all have the tiles of [board]; none, unless given. */
    private fun book(
        vararg pages: String = arrayOf("p1", "p2"),
        board: PageBoard = PageBoard(),
    ): AnnotationBook {
        var time = 1_000L
        val book = AnnotationBook(AnnotationFiles(dir), background = { it() }, foreground = { it() }, defer = { _, save -> save?.invoke() }, now = { time++ }, board = { board })
        book.keep(pages.toSet())
        pages.forEach(book::open)
        return book
    }

    /** A pad over a real book, on pages with the tiles of [board]. */
    private fun pad(
        book: AnnotationBook,
        asked: String? = null,
        showing: String? = "p1",
        board: PageBoard = PageBoard(),
    ) = Pad(asked, showing, seen = { board to book.on(it) }) { page, text, near -> book.leaveNote(page, text, near) }

    private fun ask(
        pad: NotePad,
        text: String,
        near: String? = null,
    ): JSONObject {
        var answer: JSONObject? = null
        val input = JSONObject().put("command", "note").put("text", text)
        near?.let { input.put("near", it) }
        PhoneNotes.reply(input, pad) { answer = it }
        return answer ?: throw AssertionError("no answer")
    }

    private fun look(
        pad: NotePad,
        name: String? = null,
    ): JSONObject {
        var answer: JSONObject? = null
        val input = JSONObject().put("command", "board")
        name?.let { input.put("name", it) }
        PhoneBoard.reply(input, pad) { answer = it }
        return answer ?: throw AssertionError("no answer")
    }

    private fun cellOf(told: JSONObject) = Cell(told.getInt("col"), told.getInt("row"), told.getInt("w"), told.getInt("h"))

    /** The cells a reply, or a note or sticker in one, says it lies on. */
    private fun JSONObject.cell() = cellOf(getJSONObject("cells"))

    private fun JSONObject.list(key: String) = getJSONArray(key).let { all -> (0 until all.length()).map(all::getJSONObject) }

    @Test fun theNoteGoesOnThePageTheRequestWasAskedFrom() {
        val book = book()
        // Asked from Trip while home has since been turned to Today.
        val answer = ask(pad(book, asked = "p2", showing = "p1"), "Booked the table for 8.")
        assertTrue(answer.getBoolean("ok"))
        assertEquals("Trip", answer.getString("page"))
        assertFalse(answer.getBoolean("replaced"))
        assertEquals("Booked the table for 8.", book.on("p2").notes.single().text)
        assertEquals(Author.Agent, book.on("p2").notes.single().author)
        assertTrue(book.on("p1").isEmpty)
        // And it is on disk for the next start.
        val saved = AnnotationStore.read(AnnotationFiles(dir).file("p2").readText()) as AnnotationsRead.Read
        assertEquals(book.on("p2"), saved.annotations)
    }

    @Test fun withNoRequestsPageItGoesOnTheOneShowing() {
        val book = book()
        assertEquals("Today", ask(pad(book, asked = null, showing = "p1"), "done").getString("page"))
        // The page it was asked from has been deleted since: the one showing.
        assertEquals("Trip", ask(pad(book, asked = "p7", showing = "p2"), "done").getString("page"))
        assertEquals(1, book.on("p1").notes.size)
        assertEquals(1, book.on("p2").notes.size)
    }

    @Test fun withNoPageToWriteOnNothingIsWritten() {
        for (pad in listOf(Pad(asked = null, showing = null), Pad(asked = "p9", showing = "p8"), Pad(asked = "p1", showing = "p2", names = emptyMap()))) {
            assertThrows(IllegalStateException::class.java) { ask(pad, "done") }
            assertTrue(pad.left.isEmpty())
        }
        // A request without text is no request.
        val pad = Pad()
        assertThrows(Exception::class.java) { PhoneNotes.reply(JSONObject().put("command", "note"), pad) {} }
        assertTrue(pad.left.isEmpty())
    }

    @Test fun theTextIsBoundedAndPlain() {
        val book = book()
        val pad = pad(book)
        for (text in listOf("", "   ", "\n\t", "x".repeat(AnnotationLimits.AGENT_TEXT + 1), "x".repeat(100_000))) {
            val answer = ask(pad, text)
            assertFalse(answer.getBoolean("ok"))
            assertTrue(answer.getString("error").isNotEmpty())
        }
        assertTrue(book.on("p1").isEmpty)
        // At the bound it is taken whole; markup, escapes and direction marks are letters or nothing.
        assertTrue(ask(pad, "x".repeat(AnnotationLimits.AGENT_TEXT)).getBoolean("ok"))
        assertTrue(ask(pad, "<script>alert(1)</script> ‮`rm -rf /` \u0000\$(reboot)").getBoolean("ok"))
        assertEquals("<script>alert(1)</script> `rm -rf /` \$(reboot)", book.on("p1").notes.last().text)
        assertEquals(AnnotationLimits.AGENT_TEXT, book.on("p1").notes.first().text.length)
        assertEquals(280, AnnotationLimits.AGENT_TEXT)
    }

    @Test fun notesCascadeAndOnlyTheAgentsOwnOldestEverMakesWay() {
        val book = book()
        // The owner's own things on the page: ink, a sticker, two notes, one of them where the cascade starts.
        book.draw("p1", Stroke(InkTool.Pen, InkRole.Pen, 0.006f, floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)))
        book.addSticker("p1", StickerKind.Star)
        val mine = listOf(book.addNote("p1")!!, book.addNote("p1")!!)
        book.changeNote("p1", mine[0]) { it.copy(text = "call mum") }
        book.changeNote("p1", mine[1]) { it.copy(text = "milk") }
        val before = book.on("p1")
        val pad = pad(book)
        val answers = (1..AnnotationLimits.AGENT_NOTES + 3).map { ask(pad, "did $it") }
        assertTrue(answers.all { it.getBoolean("ok") })
        // The first few fit; after that each takes the place of the agent's oldest, and says so.
        assertEquals(List(AnnotationLimits.AGENT_NOTES) { false } + List(3) { true }, answers.map { it.getBoolean("replaced") })
        val after = book.on("p1")
        assertEquals(before.strokes, after.strokes)
        assertEquals(before.stickers, after.stickers)
        assertEquals(before.notes, after.notes.filter { it.author == Author.Owner })
        assertEquals(listOf("did 4", "did 5", "did 6", "did 7"), after.notes.filter { it.author == Author.Agent }.map { it.text })
        // No note lies exactly on another.
        assertEquals(after.notes.size, after.notes.map { it.x to it.y }.toSet().size)
        // One the owner has moved is his: the next note leaves it be.
        val kept = after.notes.first { it.text == "did 4" }
        book.changeNote("p1", kept.id, raise = true) { it.copy(x = 0.5f, y = 0.7f) }
        repeat(6) { assertTrue(ask(pad, "later $it").getBoolean("ok")) }
        assertTrue(book.on("p1").notes.any { it.id == kept.id && it.text == "did 4" && it.author == Author.Owner })
        assertEquals(AnnotationLimits.AGENT_NOTES, book.on("p1").notes.count { it.author == Author.Agent })
        assertEquals(4, AnnotationLimits.AGENT_NOTES)
    }

    @Test fun aPageThatTakesNoChangeTakesNoNote() {
        // Its file can't be read: nothing is written over it, by the agent either.
        dir.mkdirs()
        val file = AnnotationFiles(dir).file("p1").apply { writeText("{ cut sho") }
        val book = book()
        val answer = ask(pad(book), "done")
        assertFalse(answer.getBoolean("ok"))
        assertEquals("{ cut sho", file.readText())
        // A session whose layout isn't saved keeps no note.
        val unkept = AnnotationBook(AnnotationFiles(dir), background = { it() }, foreground = { it() }, defer = { _, save -> save?.invoke() })
        unkept.open("p2")
        assertFalse(ask(pad(unkept, showing = "p2"), "done").getBoolean("ok"))
        assertFalse(AnnotationFiles(dir).file("p2").exists())
        // A page full of the owner's notes.
        val full = book("p3")
        repeat(AnnotationLimits.NOTES) { full.addNote("p3") }
        val refused = ask(Pad(showing = "p3", names = mapOf("p3" to "Full")) { page, text, near -> full.leaveNote(page, text, near) }, "done")
        assertFalse(refused.getBoolean("ok"))
        assertEquals(AnnotationLimits.NOTES, full.on("p3").notes.size)
        assertNull(full.on("p3").notes.firstOrNull { it.author == Author.Agent })
    }

    @Test fun theAgentIsToldOfTheNoteInOneSentence() {
        val told = PhoneBridge.INSTRUCTIONS.lines().filter { it.contains("oma-phone note") }
        assertEquals(1, told.size)
        // Boss, 2026-10-13: no note after every task; only when he asks for one.
        assertTrue(told.single().contains("Only when the user asks for a sticky note"))
        assertFalse(PhoneBridge.INSTRUCTIONS.contains("After finishing something"))
        assertTrue(told.single().contains("${AnnotationLimits.AGENT_TEXT} characters"))
        val script = moduleFile("src/main/assets/oma-phone.sh").readText()
        assertTrue(script.contains("oma-phone note [--near COL,ROW] TEXT"))
        assertTrue(script.contains("${AnnotationLimits.AGENT_TEXT} characters"))
        // The text, and the cell asked for, reach the launcher as data, never as part of a command line or of the JSON's shape.
        assertTrue(script.contains("""request=${'$'}(jq -cn --arg text "${'$'}*" --arg near "${'$'}near" '{command:"note",text:${'$'}text} + (if ${'$'}near=="" then {} else {near:${'$'}near} end)');;"""))
        // That notes keep clear of widgets is the command's doing: the agent is told so, not asked to see to it.
        assertTrue(told.single().contains("Notes go in free space by themselves"))
        assertEquals(1, PhoneBridge.INSTRUCTIONS.lines().count { it.contains("oma-phone board") && it.contains("before placing anything") })
        assertTrue(script.contains("oma-phone board [PAGE NAME]"))
        assertTrue(script.contains("""request=${'$'}(jq -cn --arg name "${'$'}*" '{command:"board"} + (if ${'$'}name=="" then {} else {name:${'$'}name} end)');;"""))
    }

    // Where it lands, and what the answer says of it.

    @Test fun onTheOwnersPageTheNoteLandsInTheFreeCellsAndSaysWhere() {
        val book = book(board = owners)
        val answer = ask(pad(book, board = owners), "Added the koi pond.")
        assertTrue(answer.getBoolean("ok"))
        assertEquals(Cell(3, 8, 3, 1), answer.cell())
        assertTrue(answer.getBoolean("clear"))
        assertFalse(answer.has("reason"))
        val note = book.on("p1").notes.single()
        assertTrue(owners.tiles.none { owners.box(it.cell).overlap(note.box()) > 0f })
        // The reply has these fields and no others.
        assertEquals(setOf("ok", "page", "replaced", "cells", "clear"), answer.keys().asSequence().toSet())
    }

    @Test fun aFullPageTakesTheNoteAndSaysItOverlaps() {
        val full = PageBoard(9, listOf(PageTile("wall.owl", Cell(0, 0, 6, 9))))
        val book = book(board = full)
        val answer = ask(pad(book, board = full), "done")
        assertTrue(answer.getBoolean("ok"))
        assertFalse(answer.getBoolean("clear"))
        assertEquals(NOTE_OVERLAPS, answer.getString("reason"))
        assertTrue(answer.getString("reason").contains("page is full"))
        assertEquals(1, book.on("p1").notes.size)
    }

    @Test fun nearIsAHintThatNeverPutsANoteOnATile() {
        val book = book(board = owners)
        val pad = pad(book, board = owners)
        // Asked for on the middle of the koi widget: it goes to the nearest clear cells and says which.
        val answer = ask(pad, "done", near = "2,4")
        assertTrue(answer.getBoolean("ok"))
        assertEquals(Cell(3, 8, 3, 1), answer.cell())
        assertTrue(answer.getBoolean("clear"))
        // On an empty page it goes where it was asked for.
        assertEquals(Cell(2, 5, 3, 2), ask(pad(book(), showing = "p2"), "done", near = " 2 , 5 ").cell())
        // What isn't a cell of the grid is refused, and nothing is written.
        val before = book.on("p1")
        for (bad in listOf("", "3", "3,", "a,b", "3;8", "-1,2", "3,8,1", "999,1", "3.5,2")) assertThrows(bad, IllegalStateException::class.java) { ask(pad, "done", near = bad) }
        for (outside in listOf("6,0", "0,9", "12,12")) {
            val refused = ask(pad, "done", near = outside)
            assertFalse(outside, refused.getBoolean("ok"))
            assertTrue(refused.getString("error").contains("6 columns by 9 rows"))
        }
        assertEquals(before, book.on("p1"))
    }

    // The board.

    @Test fun theBoardIsBehindTheSameGatesAsThePageCommands() {
        val asked = ArrayList<String>()
        assertEquals(PhoneRequests.Route.Board, PhoneRequests.admit("board", { asked += "access" }) { asked.add("pending").let { 0 } })
        assertEquals(listOf("access", "pending"), asked)
        // Without access it is refused before anything else, and it is no way round a waiting approval.
        assertThrows(IllegalStateException::class.java) { PhoneRequests.admit("board", { error("no access") }) { throw AssertionError("asked what is pending") } }
        for (pending in listOf(1, 2, 16)) assertThrows(IllegalStateException::class.java) { PhoneRequests.admit("board", {}) { pending } }
        assertFalse(PhoneBoard.COMMAND in PhoneControlPolicy.whilePending)
        assertFalse(PhoneBoard.COMMAND in PhonePages.commands)
        // The service hands it on by the route alone, after the one admit, to the one handler.
        val request = moduleFile("src/main/kotlin/dev/omalauncher/phone/PhoneService.kt").readText().substringAfter("fun request(input: JSONObject")
        assertTrue(request.indexOf("PhoneRequests.admit(") in 0 until request.indexOf("if (route == PhoneRequests.Route.Board)"))
        assertEquals(1, request.split("PhoneBoard.reply(").size - 1)
        assertFalse(request.contains("\"board\""))
    }

    @Test fun theBoardSaysWhatIsWhereOnThePage() {
        val book = book(board = owners)
        val pad = pad(book, board = owners)
        val empty = look(pad)
        assertEquals(setOf("ok", "page", "grid", "tiles", "notes", "stickers", "free"), empty.keys().asSequence().toSet())
        assertEquals("Today", empty.getString("page"))
        assertEquals(6, empty.getJSONObject("grid").getInt("columns"))
        assertEquals(9, empty.getJSONObject("grid").getInt("rows"))
        assertEquals(listOf("today.owl", "fetch.owl", "koi.owl", "owl.owl", "oma.owl"), empty.list("tiles").map { it.getString("name") })
        assertEquals(owners.tiles.map { it.cell }, empty.list("tiles").map(::cellOf))
        assertTrue(empty.list("tiles").all { it.getString("kind") == "widget" })
        assertEquals(listOf(Cell(3, 8, 3, 1)), empty.list("free").map(::cellOf))
        assertTrue(empty.list("notes").isEmpty() && empty.list("stickers").isEmpty())
        // After a note: it is listed with its true rectangle and cells, and the cells it took are no longer free.
        ask(pad, "x".repeat(200))
        val after = look(pad)
        val note = after.list("notes").single()
        assertEquals("oma", note.getString("author"))
        assertEquals(PhoneBoard.TEXT_SHOWN, note.getString("text").length)
        assertEquals(Cell(3, 8, 3, 1), note.cell())
        val saved = book.on("p1").notes.single()
        assertEquals(saved.x.toDouble(), note.getDouble("x"), 0.00006)
        assertEquals(saved.h.toDouble(), note.getDouble("h"), 0.00006)
        assertEquals(note.getDouble("x").toString(), note.getDouble("x").toString().take(6))
        assertTrue(after.list("free").isEmpty())
        // It only looked: the page is as the note left it.
        assertEquals(listOf(saved), book.on("p1").notes)
        assertTrue(pad.left.size == 1 && pad.looked == listOf("p1", "p1"))
    }

    @Test fun theBoardIsOfThePageNamedOrTheOneANoteWouldGoOn() {
        val book = book()
        // With no name: the page the request came from, as for a note; else the one showing.
        assertEquals("Trip", look(pad(book, asked = "p2", showing = "p1")).getString("page"))
        assertEquals("Today", look(pad(book, asked = null, showing = "p1")).getString("page"))
        // By name, as `page` matches names, without turning to it.
        val pad = pad(book)
        assertEquals("Trip", look(pad, "trip").getString("page"))
        assertEquals("Trip", look(pad, "page two").getString("page"))
        assertEquals(listOf("p2", "p2"), pad.looked)
        // A name that picks out no page, or several, looks at none and lists the names.
        val none = look(pad, "holiday")
        assertFalse(none.getBoolean("ok"))
        assertEquals(2, none.getJSONArray("pages").length())
        val several = look(Pad(names = mapOf("p1" to "Trip", "p2" to "Trip to Lisbon", "p3" to "Trips")), "tri")
        assertFalse(several.getBoolean("ok"))
        assertEquals(3, several.getJSONArray("matches").length())
        assertEquals(listOf("p2", "p2"), pad.looked)
        // No page to look at, or a notebook not read yet.
        assertThrows(IllegalStateException::class.java) { look(Pad(asked = null, showing = null)) }
        assertThrows(IllegalStateException::class.java) { look(Pad(names = emptyMap())) }
    }

    @Test fun theBoardListsAppsStickersAndTheOwnersNotes() {
        val board = PageBoard(9, listOf(PageTile("Camera", Cell(0, 0, 1, 1), "app"), PageTile("Maps, Mail", Cell(1, 0, 1, 1), "folder")))
        val marks =
            Annotations(
                notes = listOf(StickyNote("n1", 0.5f, 0.5f, 0.44f, 0.16f, "call mum\nabout sunday", z = 2), StickyNote("n2", 0.02f, 0.3f, 0.3f, 0.1f, "did it", author = Author.Agent, z = 1)),
                stickers = listOf(Sticker("s1", StickerKind.Star, 0.9f, 0.9f, 0.14f)),
            )
        val told = PhoneBoard.described("Today", board, marks)
        assertEquals(listOf("app" to "Camera", "folder" to "Maps, Mail"), told.list("tiles").map { it.getString("kind") to it.getString("name") })
        // Notes from the bottom of the pile up, each with who wrote it.
        assertEquals(listOf("oma" to "did it", "me" to "call mum\nabout sunday"), told.list("notes").map { it.getString("author") to it.getString("text") })
        assertEquals(Cell(3, 4, 3, 2), told.list("notes")[1].cell())
        val sticker = told.list("stickers").single()
        assertEquals("star", sticker.getString("kind"))
        assertEquals(Cell(5, 7, 1, 2), sticker.cell())
        // Free is what none of them lies on.
        val free = told.list("free").map(::cellOf)
        assertTrue(free.isNotEmpty() && free.size <= PageBoard.FREE_RECTANGLES)
        val taken = listOf(Cell(0, 0, 2, 1), Cell(3, 4, 3, 2), Cell(0, 2, 2, 2), Cell(5, 7, 1, 2))
        assertTrue(free.none { one -> taken.any { it.overlaps(one) } })
        // A page whose notes can't be read still tells its tiles, and says what is missing.
        val unread = PhoneBoard.described("Today", board, null)
        assertTrue(unread.getBoolean("ok") && unread.list("notes").isEmpty() && unread.getString("note").contains("can't be read"))
        assertEquals(2, unread.list("tiles").size)
    }

    @Test fun theBoardTellsAPictureStickerByItsName() {
        val moon = Picture(snapshotName("moon".toByteArray(), "webp"), "moon", 2f)
        val marks = Annotations(stickers = listOf(Sticker("s1", StickerKind.Heart, 0.2f, 0.2f, 0.14f), Sticker("s2", PICTURE_KIND, 0.5f, 0.5f, 0.2f, picture = moon)))
        val told = PhoneBoard.described("Today", PageBoard(9), marks).list("stickers")
        assertEquals(listOf("heart", "picture"), told.map { it.getString("kind") })
        assertFalse(told[0].has("name"))
        assertEquals("moon", told[1].getString("name"))
        // Its true rectangle: twice as wide as tall on screen.
        assertEquals(0.2, told[1].getDouble("w"), 1e-4)
        assertEquals(0.1 / PAGE_TALL, told[1].getDouble("h"), 1e-4)
    }
}
