package dev.omalauncher.phone

import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.widgets.annotations.AnnotationBook
import dev.omalauncher.widgets.annotations.AnnotationFiles
import dev.omalauncher.widgets.annotations.AnnotationLimits
import dev.omalauncher.widgets.annotations.AnnotationStore
import dev.omalauncher.widgets.annotations.AnnotationsRead
import dev.omalauncher.widgets.annotations.Author
import dev.omalauncher.widgets.annotations.InkRole
import dev.omalauncher.widgets.annotations.InkTool
import dev.omalauncher.widgets.annotations.NoteLeft
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
        assertEquals(setOf("read", "apps", "screenshot"), PhoneControlPolicy.whilePending)
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
        // Nothing but the one handler reaches the pages' annotations from the service.
        assertEquals(1, service.split("HomeAnnotations.of(").size - 1)
        assertEquals(1, service.split("leaveNote(").size - 1)
    }

    // Which page, and what the command answers.

    /** Home as the command sees it, recording what it was asked to leave and where. */
    private class Pad(
        override val asked: String? = null,
        override val showing: String? = "p1",
        val pages: Map<String, String> = mapOf("p1" to "Today", "p2" to "Trip"),
        val answer: (String, String) -> NoteLeft = { page, text -> throw AssertionError("left $text on $page") },
    ) : NotePad {
        val left = ArrayList<Pair<String, String>>()

        override fun name(page: String) = pages[page]

        override fun leave(
            page: String,
            text: String,
            then: (NoteLeft) -> Unit,
        ) {
            left += page to text
            then(answer(page, text))
        }
    }

    private val dir = Files.createTempDirectory("notes").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun book(vararg pages: String = arrayOf("p1", "p2")): AnnotationBook {
        var time = 1_000L
        val book = AnnotationBook(AnnotationFiles(dir), background = { it() }, foreground = { it() }, defer = { _, save -> save?.invoke() }, now = { time++ })
        book.keep(pages.toSet())
        pages.forEach(book::open)
        return book
    }

    /** A pad over a real book. */
    private fun pad(
        book: AnnotationBook,
        asked: String? = null,
        showing: String? = "p1",
    ) = Pad(asked, showing) { page, text -> book.leaveNote(page, text) }

    private fun ask(
        pad: NotePad,
        text: String,
    ): JSONObject {
        var answer: JSONObject? = null
        PhoneNotes.reply(JSONObject().put("command", "note").put("text", text), pad) { answer = it }
        return answer ?: throw AssertionError("no answer")
    }

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
        for (pad in listOf(Pad(asked = null, showing = null), Pad(asked = "p9", showing = "p8"), Pad(asked = "p1", showing = "p2", pages = emptyMap()))) {
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
        val refused = ask(Pad(showing = "p3", pages = mapOf("p3" to "Full")) { page, text -> full.leaveNote(page, text) }, "done")
        assertFalse(refused.getBoolean("ok"))
        assertEquals(AnnotationLimits.NOTES, full.on("p3").notes.size)
        assertNull(full.on("p3").notes.firstOrNull { it.author == Author.Agent })
    }

    @Test fun theAgentIsToldOfTheNoteInOneSentence() {
        val told = PhoneBridge.INSTRUCTIONS.lines().filter { it.contains("oma-phone note") }
        assertEquals(1, told.size)
        assertTrue(told.single().contains("After finishing something the user asked for"))
        assertTrue(told.single().contains("${AnnotationLimits.AGENT_TEXT} characters"))
        val script = moduleFile("src/main/assets/oma-phone.sh").readText()
        assertTrue(script.contains("oma-phone note TEXT"))
        assertTrue(script.contains("${AnnotationLimits.AGENT_TEXT} characters"))
        // The text reaches the launcher as data, never as part of a command line or of the JSON's shape.
        assertTrue(script.contains("""request=${'$'}(jq -cn --arg text "${'$'}*" '{command:"note",text:${'$'}text}');;"""))
    }
}
