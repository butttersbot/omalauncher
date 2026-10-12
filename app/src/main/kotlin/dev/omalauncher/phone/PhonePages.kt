package dev.omalauncher.phone

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.Box
import dev.omalauncher.widgets.annotations.NOTE_OVERLAPS
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.UNITS
import dev.omalauncher.widgets.annotations.box
import dev.omalauncher.widgets.annotations.noteText
import dev.omalauncher.widgets.annotations.scraps
import dev.omalauncher.widgets.annotations.taken
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The order every bridge request goes through, in one place: what it is, then the checks, then who handles it.
 * `status` and `stop` are answered whatever the state (stopping must always work). Everything else first needs
 * access (connected, not paused, unlocked, awake), then no approval waiting for the owner unless it only looks,
 * and only then is it handed on. Nothing is dispatched before [admit] returns. Leaving a note writes on home, so
 * it is no exception to either check; nor is `board`, which only looks at home, as `pages` does.
 */
object PhoneRequests {
    /** Who handles an admitted request. */
    enum class Route { Status, Stop, Pages, Board, Note, Screenshot, Control }

    /**
     * Runs the checks for [command] in order and says who handles it; throws what the first failing check
     * throws. [requireAccess] and [pending] are not consulted for `status` and `stop`, and [pending] not
     * unless access was given.
     */
    fun admit(
        command: String,
        requireAccess: () -> Unit,
        pending: () -> Int,
    ): Route {
        when (command) {
            "status" -> return Route.Status
            "stop" -> return Route.Stop
        }
        requireAccess()
        PhoneControlPolicy.requireNoPendingApproval(pending(), command)
        return when (command) {
            in PhonePages.commands -> Route.Pages
            PhoneBoard.COMMAND -> Route.Board
            PhoneNotes.COMMAND -> Route.Note
            "screenshot" -> Route.Screenshot
            else -> Route.Control
        }
    }
}

/** How a page name that was asked for compares with the notebook's page names. */
sealed interface PageMatch {
    /** The one page that matched, by its place from 0. */
    data class Found(val index: Int) : PageMatch

    /** More than one page matched equally well, at these places: none is chosen. */
    data class Several(val indices: List<Int>) : PageMatch

    data object None : PageMatch

    /** Not a name at all: empty, or longer than any page name is asked for by. */
    data class Refused(val reason: String) : PageMatch
}

/** Longer than this is not a page name someone said. */
const val MAX_PAGE_QUERY = 80

/**
 * As names are compared: no case, spaces around or doubled inside don't count, and a letter is the same letter
 * however it was typed (an accent as its own character, a full-width form). Normalised again after the case is
 * folded, which can itself take a letter apart.
 */
private fun plain(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return Normalizer.normalize(folded, Normalizer.Form.NFKC).trim().split(Regex("\\s+")).joinToString(" ")
}

/**
 * Finds [asked] among [names], the pages in order: the page called exactly that, else the one whose name starts
 * with it, else the one whose name contains it. The first of those three that matches anything decides: one
 * page there is [PageMatch.Found], several are [PageMatch.Several], and a weaker kind of match is not looked at.
 * Only when no name matches at all is [asked] read as a page's place in the notebook, as it is spoken:
 * "page two", "the second page", "page 2", "last page".
 */
fun matchPage(
    names: List<String>,
    asked: String,
): PageMatch {
    if (asked.trim().length > MAX_PAGE_QUERY) return PageMatch.Refused("Page name too long.")
    val want = plain(asked)
    if (want.isEmpty()) return PageMatch.Refused("Page name required.")
    val have = names.map(::plain)
    val kinds = listOf<(String) -> Boolean>({ it == want }, { it.startsWith(want) }, { it.contains(want) })
    for (matches in kinds) {
        val hits = have.indices.filter { matches(have[it]) }
        if (hits.size == 1) return PageMatch.Found(hits.single())
        if (hits.size > 1) return PageMatch.Several(hits)
    }
    pagePlace(want, names.size)?.let { return PageMatch.Found(it) }
    return PageMatch.None
}

private val PLACES =
    listOf(
        listOf("1", "one", "first", "1st"), listOf("2", "two", "second", "2nd"), listOf("3", "three", "third", "3rd"),
        listOf("4", "four", "fourth", "4th"), listOf("5", "five", "fifth", "5th"), listOf("6", "six", "sixth", "6th"),
        listOf("7", "seven", "seventh", "7th"), listOf("8", "eight", "eighth", "8th"), listOf("9", "nine", "ninth", "9th"),
        listOf("10", "ten", "tenth", "10th"), listOf("11", "eleven", "eleventh", "11th"), listOf("12", "twelve", "twelfth", "12th"),
    )

/** Words that go around a page's number when it is said aloud. */
private val AROUND = setOf("page", "my", "the", "number", "no", "#")

/**
 * The place, from 0, that [asked] (already plain) names among [count] pages: one number, in digits or as a
 * word, with nothing else but the words that go around it; or "last". Null for anything else, and for a
 * place the notebook doesn't have.
 */
internal fun pagePlace(
    asked: String,
    count: Int,
): Int? {
    val words = asked.split(' ', '.', ',').filter { it.isNotEmpty() && it !in AROUND }
    val word = words.singleOrNull() ?: return null
    val place = if (word == "last") count - 1 else PLACES.indexOfFirst { word in it }.takeIf { it >= 0 } ?: word.toIntOrNull()?.minus(1) ?: return null
    return place.takeIf { it in 0 until count }
}

/** The notebook's pages as the bridge's page commands see them. Main thread, like the notebook. */
interface HomeBook {
    /** The page names in order; null until the notebook has been read. */
    val pages: List<String>?

    /** The place, from 0, of the page home shows (of two side by side, the one last touched); null when home isn't there to say. */
    val showing: Int?

    /** The place of the page the widget file [widget] is on; null for a file the notebook doesn't have. */
    fun pageOf(widget: String): Int?

    /** Whether [widget] has its cells on that page yet. */
    fun placed(widget: String): Boolean

    /** Turns home to the page at [index] and brings the launcher to the front. */
    fun show(index: Int)
}

/**
 * The bridge's page commands: `pages` lists the page names, `page` turns home to one by name, and `where` says
 * which page a widget file is on. They read the notebook and turn its pages; nothing else on the phone is
 * touched, no other app, and a name that doesn't pick out exactly one page changes nothing.
 */
object PhonePages {
    val commands = setOf("pages", "page", "where")

    fun reply(
        input: JSONObject,
        book: HomeBook,
    ): JSONObject {
        val names = book.pages ?: error("Home is still loading. Try again in a moment.")
        return when (val command = input.getString("command")) {
            "pages" -> listed(JSONObject().put("ok", true), names, book)
            "page" -> {
                val asked = input.getString("name")
                when (val match = matchPage(names, asked)) {
                    is PageMatch.Found -> {
                        book.show(match.index)
                        JSONObject().put("ok", true).put("page", names[match.index])
                    }
                    is PageMatch.Several ->
                        listed(JSONObject().put("ok", false).put("error", "More than one page matches. Nothing was changed; ask which one.").put("matches", JSONArray(match.indices.map { names[it] })), names, book)
                    PageMatch.None -> listed(JSONObject().put("ok", false).put("error", "No page matches that name. Nothing was changed."), names, book)
                    is PageMatch.Refused -> error(match.reason)
                }
            }
            "where" -> {
                val widget = widgetFile(input.getString("widget"))
                val at = book.pageOf(widget)
                val output = JSONObject().put("ok", true).put("widget", widget)
                if (at == null || at !in names.indices) {
                    output.put("placed", false).put("note", "Not on a page yet. A new file takes a moment; try again.")
                } else {
                    output.put("page", names[at]).put("placed", book.placed(widget))
                    if (!book.placed(widget)) output.put("note", "Its cells are given when home next shows; if that page is full it moves on. Ask again before saying where it is.")
                    output
                }
            }
            else -> error("Unknown phone command: $command")
        }
    }

    private fun listed(
        output: JSONObject,
        names: List<String>,
        book: HomeBook,
    ): JSONObject {
        output.put("pages", JSONArray(names))
        book.showing?.let(names::getOrNull)?.let { output.put("showing", it) }
        return output
    }

    /** A widget's file name as the notebook keys it, from what was asked: a bare `*.owl` name, with or without the ending. */
    internal fun widgetFile(asked: String): String {
        val name = asked.trim().let { if (it.endsWith(".owl")) it else "$it.owl" }
        require(name.length in 5..200 && !name.startsWith(".") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Give the widget's file name, like weather.owl." }
        return name
    }
}

/** Home's pages as the bridge's note and board commands see them. Main thread, like the notebook. */
interface NotePad {
    /** The id of the home page the request that is running now was sent from; null when none is running, or it came from no page. */
    val asked: String?

    /** The id of the page home shows (of two side by side, the one last touched); null when home isn't there to say. */
    val showing: String?

    /** The ids of the notebook's pages in order; none until it has been read. */
    val pages: List<String>

    /** The name of the page with the id [page]; null if the notebook has no such page, or hasn't been read. */
    fun name(page: String): String?

    /**
     * Leaves a note saying [text] on [page], near the cell [near] (column, row) when one is asked for, and tells
     * [then], once, what came of it; see [dev.omalauncher.widgets.annotations.leaveAgentNote].
     */
    fun leave(
        page: String,
        text: String,
        near: Pair<Int, Int>?,
        then: (NoteLeft) -> Unit,
    )

    /**
     * Looks at [page] and tells [then], once, what is on it: its grid and tiles, and its notes and stickers,
     * or null for those when they can't be read. Nothing is changed.
     */
    fun look(
        page: String,
        then: (PageBoard, Annotations?) -> Unit,
    )
}

/** The page a command that names none is about: the one the running request was asked from, or else the one home shows. */
private fun NotePad.here(): String? = listOfNotNull(asked, showing).firstOrNull { name(it) != null }

/** A cell rectangle as the agent reads and writes it: column and row from 0, and how many of each. */
private fun cellsJson(cell: Cell) = JSONObject().put("col", cell.x).put("row", cell.y).put("w", cell.w).put("h", cell.h)

/**
 * The bridge's `note` command: the agent leaves one sticky note saying what it did (PRD AC-29). It goes on the
 * page the running request was asked from, or, with no such request or page, on the page home shows. That is
 * all it does: the text is drawn as plain letters and never read as anything else, it is no longer than a
 * note from an agent may be, it lands on that one page clear of the tiles and of what is stuck there, and of
 * what is already there it can only take the place of the agent's own oldest untouched note. Nothing of the
 * owner's is moved, changed or removed, no page is turned, and nothing outside home is touched.
 *
 * Where it lands is the command's doing, not the agent's: `near` ("COL,ROW", a cell of the page's grid) only
 * says where to start looking, and a note is never put on a tile because it was asked for there. The answer
 * says which cells it lies on, and `clear` is false, with the reason, only when the page had no clear place left.
 */
object PhoneNotes {
    const val COMMAND = "note"

    private val CELL = Regex("\\s*(\\d{1,2})\\s*,\\s*(\\d{1,2})\\s*")

    fun reply(
        input: JSONObject,
        pad: NotePad,
        reply: (JSONObject) -> Unit,
    ) {
        val text = input.getString("text")
        val near =
            if (!input.has("near")) {
                null
            } else {
                val cell = CELL.matchEntire(input.getString("near")) ?: error("Give --near as COL,ROW, a cell from oma-phone board, like 3,8. Nothing was written.")
                cell.groupValues[1].toInt() to cell.groupValues[2].toInt()
            }
        val page = pad.here() ?: error("Home isn't showing a page to leave the note on. Nothing was written.")
        pad.leave(page, text, near) { left ->
            reply(
                when (left) {
                    is NoteLeft.Left -> {
                        val output = JSONObject().put("ok", true).put("page", pad.name(page) ?: "").put("replaced", left.replaced != null)
                        output.put("cells", cellsJson(left.cells)).put("clear", left.clear)
                        if (!left.clear) output.put("reason", NOTE_OVERLAPS)
                        output
                    }
                    is NoteLeft.Refused -> JSONObject().put("ok", false).put("error", left.reason)
                },
            )
        }
    }
}

/**
 * The bridge's `board` command: what is where on one home page, in numbers an agent can reason with, so it
 * need not guess from a picture. The page's grid; every tile with its cells; every note and sticker with its
 * true rectangle, both as fractions of the page and as the cells it lies on; and `free`, the biggest
 * rectangles of cells with nothing on them. It is the page named, as `page` matches names, or with no name
 * the page a note would go on. It only looks: nothing is changed and no page is turned.
 */
object PhoneBoard {
    const val COMMAND = "board"

    /** How much of a note's text is told: enough to know which note it is. */
    const val TEXT_SHOWN = 60

    fun reply(
        input: JSONObject,
        pad: NotePad,
        reply: (JSONObject) -> Unit,
    ) {
        val ids = pad.pages
        val names = ids.map { pad.name(it) ?: "" }
        if (ids.isEmpty()) error("Home is still loading. Try again in a moment.")
        val asked = input.optString("name")
        val page =
            if (asked.isBlank()) {
                pad.here() ?: error("Home isn't showing a page. Name one: oma-phone board PAGE NAME.")
            } else {
                when (val match = matchPage(names, asked)) {
                    is PageMatch.Found -> ids[match.index]
                    is PageMatch.Several -> return reply(JSONObject().put("ok", false).put("error", "More than one page matches; ask which one.").put("matches", JSONArray(match.indices.map { names[it] })).put("pages", JSONArray(names)))
                    PageMatch.None -> return reply(JSONObject().put("ok", false).put("error", "No page matches that name.").put("pages", JSONArray(names)))
                    is PageMatch.Refused -> error(match.reason)
                }
            }
        pad.look(page) { board, marks -> reply(described(pad.name(page) ?: "", board, marks)) }
    }

    /** The page called [name] as the answer tells it; [marks] is null when its notes and stickers can't be read. */
    internal fun described(
        name: String,
        board: PageBoard,
        marks: Annotations?,
    ): JSONObject {
        val output = JSONObject().put("ok", true).put("page", name).put("grid", JSONObject().put("columns", board.columns).put("rows", board.rows))
        output.put("tiles", JSONArray(board.tiles.map { cellsJson(it.cell).put("name", it.name).put("kind", it.kind) }))

        fun placed(box: Box) = JSONObject().put("x", share(box.x)).put("y", share(box.y)).put("w", share(box.w)).put("h", share(box.h)).put("cells", cellsJson(board.cells(box)))
        val notes = marks?.notes.orEmpty().sortedBy { it.z }
        output.put("notes", JSONArray(notes.map { placed(it.box()).put("author", it.author.id).put("text", noteText(it.text, TEXT_SHOWN)) }))
        output.put("stickers", JSONArray(marks?.stickers.orEmpty().map { placed(it.taken()).put("kind", it.kind.id) }))
        output.put("free", JSONArray(board.free(marks?.scraps().orEmpty()).map(::cellsJson)))
        if (marks == null) output.put("note", "This page's notes and stickers can't be read, so none are listed and free counts tiles only.")
        return output
    }

    /** A fraction of the page as it is told: to the step fractions are kept in, with no stray digits after it. */
    private fun share(value: Float) = (value * UNITS).roundToInt() / UNITS.toDouble()
}

/**
 * Home, for what reaches it from outside its activity but inside this process: the agent's bridge asking for a
 * page, and a request noting the page it was sent from. Nothing here is an intent, so no other app can send it.
 */
object HomeDoor {
    /** What the home activity offers while it lives. */
    interface Home {
        /** The id of the page showing: of two side by side, the one last touched. */
        val showing: String

        /** A page was asked for: [take] it and turn there. Main thread. */
        fun turn()
    }

    /** A page asked for this long ago and never shown is forgotten, so home doesn't turn by itself later on. */
    const val ASK_MILLIS = 10_000L

    @Volatile var home: Home? = null
        private set

    private var wanted: Pair<String, Long>? = null

    fun enter(home: Home) {
        this.home = home
    }

    /** Only the activity that is home now leaves: one being replaced doesn't take its successor with it. */
    fun leave(home: Home) {
        if (this.home === home) this.home = null
    }

    /** Asks home for the page with the id [page]: at once if it is there, otherwise when it next starts, if that is soon. */
    @Synchronized fun ask(
        page: String,
        now: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        wanted = page to now
        home?.turn()
    }

    /** The page id asked for, once. */
    @Synchronized fun take(now: Long = android.os.SystemClock.elapsedRealtime()): String? {
        val (page, at) = wanted ?: return null
        wanted = null
        return page.takeIf { now - at in 0..ASK_MILLIS }
    }
}
