package dev.omalauncher.phone

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.Box
import dev.omalauncher.widgets.annotations.NOTE_OVERLAPS
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.LibrarySticker
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerChoice
import dev.omalauncher.widgets.annotations.StickerKind
import dev.omalauncher.widgets.annotations.StickerLibrary
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
    enum class Route { Status, Stop, Pages, Board, Note, Stickers, PageEdits, Looks, LockStar, Screenshot, Control }

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
            PhoneStickers.COMMAND -> Route.Stickers
            PhonePageEdits.COMMAND -> Route.PageEdits
            PhoneLooks.COMMAND -> Route.Looks
            PhoneLockStar.COMMAND -> Route.LockStar
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

    /** The kind a sticker that is a picture is told as; its `name` says which picture. */
    const val PICTURE_STICKER = "picture"

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
        output.put("notes", JSONArray(notes.map { placed(it.box()).put("id", it.id).put("author", it.author.id).put("text", noteText(it.text, TEXT_SHOWN)) }))
        // A picture sticker is told by what its picture is called, as it has no kind of its own.
        output.put("stickers", JSONArray(marks?.stickers.orEmpty().map { s -> placed(s.taken()).put("id", s.id).put("kind", if (s.picture == null) s.kind.id else PICTURE_STICKER).putOpt("name", s.picture?.name).put("size", (s.size * 100).roundToInt()).put("turn", s.turn) }))
        output.put("free", JSONArray(board.free(marks?.scraps().orEmpty()).map(::cellsJson)))
        if (marks == null) output.put("note", "This page's notes and stickers can't be read, so none are listed and free counts tiles only.")
        return output
    }

    /** A fraction of the page as it is told: to the step fractions are kept in, with no stray digits after it. */
    private fun share(value: Float) = (value * UNITS).roundToInt() / UNITS.toDouble()
}

/** The stickers as the bridge's sticker commands reach them. Main thread, like the notebook. */
interface StickerPad {
    /** Looks at the owner's sticker library and tells [then], once, what is in it; null where there is none. */
    fun library(then: (StickerLibrary?) -> Unit)

    /** Sticks [choice] on [page] near [near] (fractions of the page), clear of what is there, and tells [then] its id, or null if the page took none. */
    fun stick(
        page: String,
        choice: StickerChoice,
        near: Pair<Float, Float>?,
        then: (String?) -> Unit,
    )

    /** Changes the sticker [id] on [page]; false if nothing changed. */
    fun change(
        page: String,
        id: String,
        edit: (Sticker) -> Sticker,
    ): Boolean

    /** Takes the sticker [id] off [page]; false if the page can't be changed. */
    fun remove(
        page: String,
        id: String,
    ): Boolean
}

/**
 * The bridge's sticker commands: the stickers there are, one stuck on a home page, and one that is there turned,
 * resized, moved to a cell or taken off, each by its id from `board`. They do in one call, and answer with what
 * came of it, what would otherwise be a dozen taps aimed by eye. They touch stickers only: no tile, note or page
 * is changed, and a new sticker lands clear of what is on the page as one picked by hand does.
 */
object PhoneStickers {
    const val COMMAND = "sticker"

    /** A sticker's size as it is asked for: its longer side in hundredths of the page's width. */
    val SIZES = 4.0..50.0

    private val CELL = Regex("\\s*(\\d{1,2})\\s*,\\s*(\\d{1,2})\\s*")

    /** What picking a sticker by name came to. */
    internal sealed interface Picked {
        data class One(val choice: StickerChoice, val name: String) : Picked

        data class Several(val names: List<String>) : Picked

        data object None : Picked
    }

    /** A library picture as it is named to the agent: under its folder, when it is in one. */
    internal fun called(sticker: LibrarySticker) = if (sticker.group.isEmpty()) sticker.name else "${sticker.group}/${sticker.name}"

    /** The sticker [name] means: a built-in one by its word, else a library picture by its full name, else by its name in any folder. */
    internal fun pick(
        name: String,
        library: StickerLibrary?,
    ): Picked {
        val asked = name.trim().lowercase()
        StickerKind.entries.firstOrNull { it.id == asked }?.let { return Picked.One(StickerChoice.BuiltIn(it), it.id) }
        val all = library?.groups.orEmpty().flatMap { it.stickers }
        val found = all.filter { called(it).lowercase() == asked }.ifEmpty { all.filter { it.name.lowercase() == asked } }
        return when {
            library == null || found.isEmpty() -> Picked.None
            found.size == 1 -> Picked.One(StickerChoice.Pictured(library.file(found[0]), found[0].name), called(found[0]))
            else -> Picked.Several(found.map(::called))
        }
    }

    private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)

    private fun cell(text: String): Pair<Int, Int> {
        val cell = CELL.matchEntire(text) ?: error("Give a cell as COL,ROW from oma-phone board, like 3,8. Nothing was changed.")
        return cell.groupValues[1].toInt() to cell.groupValues[2].toInt()
    }

    /** The middle of the cell [at] of [board], as fractions of the page. */
    private fun middle(
        board: PageBoard,
        at: Pair<Int, Int>,
    ): Pair<Float, Float> {
        require(at.first < board.columns && at.second < board.rows) { "That cell is off the page: its grid is ${board.columns} by ${board.rows}. Nothing was changed." }
        return board.box(Cell(at.first, at.second, 1, 1)).let { it.x + it.w / 2f to it.y + it.h / 2f }
    }

    /** The page the command is about: the one named, or the one a note would go on. */
    private fun page(
        input: JSONObject,
        pad: NotePad,
    ): String {
        val ids = pad.pages
        if (ids.isEmpty()) error("Home is still loading. Try again in a moment.")
        val asked = input.optString("page")
        if (asked.isBlank()) return pad.here() ?: error("Home isn't showing a page. Name one with --page.")
        val names = ids.map { pad.name(it) ?: "" }
        return when (val match = matchPage(names, asked)) {
            is PageMatch.Found -> ids[match.index]
            is PageMatch.Refused -> error(match.reason)
            else -> error("No one page matches \"$asked\". Pages: ${names.joinToString(", ")}. Nothing was changed.")
        }
    }

    /** What happens after the bridge's own checks runs later, where nothing catches for it: a failure there is an answer too. */
    private inline fun answering(
        reply: (JSONObject) -> Unit,
        run: () -> Unit,
    ) {
        try {
            run()
        } catch (e: Exception) {
            reply(fail(e.message ?: "Phone control failed"))
        }
    }

    /** Answers with the sticker [id] as it now is on [page]. */
    private fun told(
        pad: NotePad,
        page: String,
        id: String,
        reply: (JSONObject) -> Unit,
    ) = pad.look(page) { board, marks ->
        answering(reply) {
            val sticker = marks?.stickers?.firstOrNull { it.id == id } ?: error("The sticker is not on that page any more.")
            val box = sticker.taken()
            // Where it now lies on a tile or on another note or sticker, the answer says so: it was asked for, and is not refused.
            val others = board.tiles.map { board.box(it.cell) } + marks?.notes.orEmpty().map { it.box() } + marks?.stickers.orEmpty().filter { it.id != id }.map { it.taken() }
            reply(
                JSONObject().put("ok", true).put("page", pad.name(page) ?: "").put("id", id)
                    .put("kind", if (sticker.picture == null) sticker.kind.id else PhoneBoard.PICTURE_STICKER).putOpt("name", sticker.picture?.name)
                    .put("size", (sticker.size * 100).roundToInt()).put("turn", sticker.turn).put("cells", cellsJson(board.cells(box)))
                    .put("clear", others.none { it.overlap(box) > 0f }),
            )
        }
    }

    fun reply(
        input: JSONObject,
        pad: NotePad,
        stickers: StickerPad,
        reply: (JSONObject) -> Unit,
    ) {
        val action = input.getString("action")
        if (action == "list") {
            return stickers.library { library ->
                answering(reply) {
                    reply(JSONObject().put("ok", true).put("builtIn", JSONArray(StickerKind.entries.map { it.id })).put("pictures", JSONArray(library?.groups.orEmpty().flatMap { it.stickers }.map(::called))))
                }
            }
        }
        val page = page(input, pad)
        when (action) {
            "add" -> {
                val name = input.getString("name")
                val near = input.optString("near").takeIf { it.isNotEmpty() }?.let(::cell)
                stickers.library { library ->
                    answering(reply) {
                        when (val picked = pick(name, library)) {
                            Picked.None -> reply(fail("No sticker is called \"$name\". Run oma-phone stickers. Nothing was stuck."))
                            is Picked.Several -> reply(fail("More than one sticker is called that; give its folder too. Nothing was stuck.").put("matches", JSONArray(picked.names)))
                            is Picked.One ->
                                pad.look(page) { board, _ ->
                                    answering(reply) {
                                        stickers.stick(page, picked.choice, near?.let { middle(board, it) }) { id ->
                                            if (id == null) reply(fail("That page took no sticker: it is full, can't be written now, or the picture can't be read.")) else told(pad, page, id, reply)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
            "turn", "size", "move" -> {
                val id = input.getString("id")
                val value = input.getString("value")
                pad.look(page) { board, marks ->
                    answering(reply) {
                        val was = marks?.stickers?.firstOrNull { it.id == id } ?: error("No sticker $id is on that page. Run oma-phone board for ids. Nothing was changed.")
                        val next =
                            when (action) {
                                "turn" -> was.copy(turn = value.trim().toIntOrNull()?.takeIf { it in -360..360 } ?: error("Give the turn in whole degrees, like 15 or -30. Nothing was changed."))
                                "size" -> was.copy(size = (value.trim().toDoubleOrNull()?.takeIf { it in SIZES } ?: error("Give the size as hundredths of the page's width, ${SIZES.start.toInt()} to ${SIZES.endInclusive.toInt()}. Nothing was changed.")).toFloat() / 100f)
                                else -> middle(board, cell(value)).let { was.copy(x = it.first, y = it.second) }
                            }
                        stickers.change(page, id) { next }
                        told(pad, page, id, reply)
                    }
                }
            }
            "remove" -> {
                val id = input.getString("id")
                pad.look(page) { _, marks ->
                    answering(reply) {
                        if (marks?.stickers?.none { it.id == id } != false) error("No sticker $id is on that page. Run oma-phone board for ids. Nothing was removed.")
                        if (!stickers.remove(page, id)) error("That page can't be changed now. Nothing was removed.")
                        reply(JSONObject().put("ok", true).put("page", pad.name(page) ?: "").put("removed", id))
                    }
                }
            }
            else -> error("Unknown sticker action: $action")
        }
    }
}

/** The notebook's pages as the bridge's page-editing commands reach them. Main thread, like the notebook. */
interface PagePad {
    /** A new, empty page after the last; its id, or null while the notebook can't take one. */
    fun add(): String?

    /** Whether a page other than [except] is already called [name]. */
    fun taken(
        name: String,
        except: String?,
    ): Boolean

    /** Calls [page] by [name]; false, and nothing changed, for a name with nothing in it. */
    fun rename(
        page: String,
        name: String,
    ): Boolean

    /** Deletes the empty page [page]; false, and nothing changed, if it is the only page or can't be deleted now. */
    fun delete(page: String): Boolean
}

/**
 * The bridge's page-editing commands: a new page, a page renamed, an empty page deleted. Each is one call that
 * answers with the pages as they then are. A page with anything on it, a tile, a note, a sticker or ink, is
 * never deleted from here: that is the owner's to do in the overview, where he is asked what becomes of it.
 */
object PhonePageEdits {
    const val COMMAND = "pageedit"

    private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)

    private fun pages(pad: NotePad) = JSONArray(pad.pages.map { pad.name(it) ?: "" })

    private fun found(
        pad: NotePad,
        asked: String,
    ): String {
        val ids = pad.pages
        val names = ids.map { pad.name(it) ?: "" }
        return when (val match = matchPage(names, asked)) {
            is PageMatch.Found -> ids[match.index]
            is PageMatch.Refused -> error(match.reason)
            else -> error("No one page matches \"$asked\". Pages: ${names.joinToString(", ")}. Nothing was changed.")
        }
    }

    fun reply(
        input: JSONObject,
        pad: NotePad,
        edits: PagePad,
        reply: (JSONObject) -> Unit,
    ) {
        if (pad.pages.isEmpty()) error("Home is still loading. Try again in a moment.")
        when (val action = input.getString("action")) {
            "add" -> {
                val name = input.optString("name").trim()
                if (name.isNotEmpty() && edits.taken(name, null)) error("A page is already called \"$name\". Nothing was added.")
                val id = edits.add() ?: error("Home can't take a new page now. Nothing was added.")
                if (name.isNotEmpty()) edits.rename(id, name)
                reply(JSONObject().put("ok", true).put("page", pad.name(id) ?: "").put("pages", pages(pad)))
            }
            "rename" -> {
                val id = found(pad, input.getString("name"))
                val to = input.getString("to").trim()
                if (edits.taken(to, id)) error("Another page is already called \"$to\". Nothing was changed.")
                if (!edits.rename(id, to)) error("That is not a name a page can have. Nothing was changed.")
                reply(JSONObject().put("ok", true).put("page", pad.name(id) ?: "").put("pages", pages(pad)))
            }
            "delete" -> {
                val id = found(pad, input.getString("name"))
                val name = pad.name(id) ?: ""
                pad.look(id) { board, marks ->
                    try {
                        check(board.tiles.isEmpty() && marks != null && marks.isEmpty) { "\"$name\" has things on it. Only an empty page is deleted from here; the user deletes this one in the overview. Nothing was changed." }
                        check(edits.delete(id)) { "\"$name\" can't be deleted: it is the only page, or home can't be changed now. Nothing was changed." }
                        reply(JSONObject().put("ok", true).put("deleted", name).put("pages", pages(pad)))
                    } catch (e: Exception) {
                        reply(fail(e.message ?: "Phone control failed"))
                    }
                }
            }
            else -> error("Unknown page action: $action")
        }
    }
}

/** The launcher's looks as the bridge's look command reaches them. Main thread. */
interface LookPad {
    /** The looks there are, by id, Console first. */
    val looks: List<String>

    /** The id of the look that is picked. */
    val current: String

    /** Draws the launcher from the look [id] from now on, and remembers it. */
    fun pick(id: String)
}

/**
 * The bridge's `look` command: which looks there are and which is picked, or, with a name, that look picked.
 * A look changes how home is drawn and nothing else: no tile, note or page moves, and picking another puts it back.
 */
object PhoneLooks {
    const val COMMAND = "look"

    private val SPACES = Regex("[\\s_]+")

    /** The look [asked] names, as it is said or as its id is written: "tiny magazine", "Tiny-Magazine". */
    internal fun match(
        looks: List<String>,
        asked: String,
    ): String? {
        val want = asked.trim().lowercase().replace(SPACES, "-")
        return looks.firstOrNull { it.lowercase() == want }
    }

    fun reply(
        input: JSONObject,
        pad: LookPad,
    ): JSONObject {
        val asked = input.optString("name")
        if (asked.isNotBlank()) {
            val id = match(pad.looks, asked) ?: error("No look is called \"$asked\". Looks: ${pad.looks.joinToString(", ")}. Nothing was changed.")
            pad.pick(id)
        }
        return JSONObject().put("ok", true).put("look", pad.current).put("looks", JSONArray(pad.looks))
    }
}

/** LockStar's layout as the bridge's lockstar command reaches it. Its calls block: not on the main thread. */
interface LockStarPad {
    val names: List<String>

    /** Takes LockStar's layout as [name] and says what it holds. */
    fun capture(name: String): JSONObject

    /** Hands [name] back to LockStar; true if it answered. */
    fun apply(name: String): Boolean
}

/**
 * The bridge's `lockstar` command: the lock-screen layouts kept, one taken from LockStar as it now is, or one
 * handed back to it. Applying changes the owner's lock screen, so it is for when he asked for exactly that.
 */
object PhoneLockStar {
    const val COMMAND = "lockstar"

    fun reply(
        input: JSONObject,
        pad: LockStarPad,
    ): JSONObject =
        when (val action = input.optString("action", "list")) {
            "list" -> JSONObject().put("ok", true).put("captures", JSONArray(pad.names))
            "capture" -> pad.capture(input.getString("name")).put("ok", true)
            "apply" -> {
                val name = input.getString("name")
                val answered = pad.apply(name)
                JSONObject().put("ok", answered).put("applied", name).also { if (!answered) it.put("error", "LockStar did not answer in time. Look at the lock screen before trying again.") }
            }
            else -> error("Unknown lockstar action: $action")
        }
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
