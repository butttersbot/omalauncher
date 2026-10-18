package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookPage
import dev.omalauncher.widgets.layout.PAGE_START
import dev.omalauncher.widgets.layout.VerbJson
import dev.omalauncher.widgets.layout.applyVerb
import org.json.JSONArray
import kotlin.math.abs
import org.json.JSONObject

/*
 * What an agent asks of a canvas, in its own words (Soma Canvas slice 6, PRD §5): "Needs you, a card, top left of the page
 * called Soma". Semantic placement turns an ask into exact verbs against the page as it is now: the asked-for spot first
 * (shrinking a little), then anywhere on that page, then the next page with room, then a new page. Agents arrange modules
 * only, and only when Boss asks (his rule, 2026-10-16): nothing here acts by itself, and nothing reads the verb log to learn
 * where he likes things. Every request is one restore point: undo puts back what it changed, as far as that still applies.
 *
 * The same words reach every screen: `soma canvas …` on a computer, `oma-phone canvas …` on the Fold. [CanvasCommands] reads
 * them; a screen's [CanvasPad] holds its canvas and applies the verbs.
 */

/** A size an agent names. Its cells are the Fold's on six columns ([cells]), a third bigger on a wider grid. */
enum class SizeWord(val word: String, private val fold: Pair<Int, Int>) {
    Dot("dot", 1 to 1),
    Line("line", 3 to 1),
    Card("card", 3 to 2),
    CardWide("card-wide", 6 to 2),
    CardBig("card-big", 4 to 3),
    Full("full", 6 to 3),
    ;

    /** Its cells on a grid of [columns] by [rows]: full is the grid's width; a card or a big card gains a row on a wide grid. */
    fun cells(columns: Int, rows: Int): Pair<Int, Int> {
        if (this == Dot) return 1 to 1
        val wide = columns > 6
        val w = if (this == Full) columns else if (wide) (fold.first * 4 + 2) / 3 else fold.first
        val h = if (wide && (this == Card || this == CardBig)) fold.second + 1 else fold.second
        return w.coerceIn(1, columns) to h.coerceIn(1, rows)
    }

    companion object {
        fun of(word: String) = entries.firstOrNull { it.word == word.trim().lowercase() }
    }
}

/** Where on a page an agent asks for something: a corner, an edge, anywhere, or beside the module [of]. */
enum class Spot(val word: String, val beside: Boolean = false) {
    Any("any"), TopLeft("top-left"), TopRight("top-right"), BottomLeft("bottom-left"), BottomRight("bottom-right"), Top("top"), Bottom("bottom"),
    RightOf("right-of", true), LeftOf("left-of", true), Below("below", true), Above("above", true),
    ;

    companion object {
        fun of(word: String) = entries.firstOrNull { it.word == word.trim().lowercase() }
    }
}

data class Where(val spot: Spot = Spot.Any, val of: String? = null)

/** One thing an agent asks. Pages are named as he sees them; modules by their key or their kind's id ("needs", "handoff:soma"). */
sealed interface CanvasAsk {
    data class PageMake(val name: String) : CanvasAsk

    data class PageRename(val page: String, val name: String) : CanvasAsk

    data class PageRemove(val page: String) : CanvasAsk

    /** A module put on a page (the one [where] is beside, or [page], or the first). One already on the canvas is moved there. */
    data class Place(val module: String, val size: SizeWord?, val where: Where, val page: String?) : CanvasAsk

    /** A module moved, at its size, to [where] on [page] (its own page when null). */
    data class Move(val module: String, val where: Where, val page: String?) : CanvasAsk

    /** A module at another size, where it is if that fits, else as near it on its page as there is room. */
    /** [page] says which copy, when the module is on more than one page. */
    data class Resize(val module: String, val size: SizeWord, val page: String? = null) : CanvasAsk

    data class Remove(val module: String, val page: String? = null) : CanvasAsk
}

/**
 * What a screen holds to itself when an agent arranges it: cells [pinned] on every page (the desktop's chat, drawn on each),
 * the columns a page has beside them ([span]), keys agents never move or remove ([kept]), pages never removed ([keptPages]), a module's size when none is asked for
 * ([sizeOf]), and a free id for a new page ([pageId]).
 */
class ScreenRules(
    val pinned: (Notebook) -> List<Cell> = { emptyList() },
    /** How many columns a page has for modules, beside what is pinned: the width a size word is measured on. */
    val span: (Notebook) -> Int = { it.columns },
    val kept: Set<String> = emptySet(),
    val keptPages: Set<String> = emptySet(),
    val sizeOf: (ModuleRef, Notebook) -> Pair<Int, Int> = { ref, n -> ref.kind.size.first.coerceAtMost(n.columns) to ref.kind.size.second.coerceAtMost(n.rows) },
    val pageId: (Notebook) -> String = { n -> generateSequence(1) { it + 1 }.map { "p$it" }.first { id -> n.pages.none { it.id == id } } },
    /** Whether agents may put the shared library's widgets here by their file name ("watchlist.owl"), as modules are. */
    val widgets: Boolean = false,
    /** Whether the library has the widget a place names. */
    val widgetKnown: (String) -> Boolean = { true },
    /**
     * The one page this screen shows, when it shows only one (the car): a template is laid onto it, never on a page
     * nobody would see (round-2 review).
     */
    val shownPage: ((Notebook) -> String?)? = null,
)

/** A widget from the shared library, as an agent names it: its file name. */
private val WIDGET_FILE = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.owl")

/**
 * Words that say what the module [word] is: its key, or its kind's id or title ("needs", "Needs you", "handoff:soma"). Null when
 * none. The chat is a module only to be beside ([chat]): agents never place or move it.
 */
fun moduleKeyOf(word: String, chat: Boolean = false): String? {
    val copy = Regex("#([0-9]+)$").find(word.trim())?.groupValues?.get(1)?.toIntOrNull()
    if (copy != null && copy !in 2..99) return null
    val said = word.trim().removePrefix(MODULE_PREFIX).substringBefore('#')
    val kindWord = said.substringBefore(':').lowercase().replace(Regex("[\\s_-]+"), " ").trim()
    val project = said.substringAfter(':', "").ifEmpty { null }
    val kind = ModuleKind.entries.firstOrNull { it.id == kindWord.replace(" ", "") || it.title.lowercase() == kindWord } ?: return null
    if (kind.chatScreen && !chat) return null
    return moduleKey(kind, project)?.let { if (copy != null) moduleRef(it)!!.copy(copy = copy).key else it }
}

/** The copies of [base]'s module on [n], each with the page it is on. */
private fun copies(n: Notebook, base: String): List<Pair<String, NotebookPage>> =
    n.pages.flatMap { p -> p.items.filter { moduleRef(it.key)?.base == base }.map { it.key to p } }

/**
 * Which copy the agent's [word] means: the one it names ("needs#2"), else the one on [page] when there is one, else the only
 * one. Several and no page: refused, naming them. None: the first copy's key, not on the canvas yet.
 */
private fun copyOf(n: Notebook, word: String, key: String, page: String?): String {
    // A widget is on the canvas once: there are no copies to choose between.
    val ref = moduleRef(key) ?: return key
    if (ref.copy > 1 || '#' in word) return key
    val all = copies(n, key)
    page?.let { p -> all.firstOrNull { it.second.id == p }?.let { return it.first } }
    return when (all.size) {
        0 -> key
        1 -> all.single().first
        else -> refuse("${moduleTitle(key)} is on more than one page (${all.joinToString(", ") { (k, p) -> "${shortName(k)} on \"${p.name}\"" }}): say which, by that name or with --page. Nothing was changed.")
    }
}

/** What an agent calls a module: its key without the "module:" in front ("needs", "handoff:soma", "needs#2"). */
fun shortName(key: String) = key.removePrefix(MODULE_PREFIX)

/** What resolving an ask came to: the exact [verbs] to apply, in order, and what they do in words ([said]). */
data class Resolved(val verbs: List<CanvasVerb>, val said: String)

/** Why an ask can't be done, in words for the agent to pass on. Nothing changed. */
class AskRefused(message: String) : Exception(message)

internal fun refuse(why: String): Nothing = throw AskRefused(why)

/**
 * [typed] as a page's name: its spaces made single, and refused when it is empty, longer than 40 characters, or holds a
 * character nobody can see or type (a control or format character: a newline, a zero-width space).
 */
fun pageNameOf(typed: String): String {
    val name = typed.trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty() || name.length > 40) refuse("A page's name is 1 to 40 characters. Nothing was changed.")
    if (name.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) refuse("A page's name can't hold invisible characters. Nothing was changed.")
    return name
}

/** The page [asked] names in [n]: its id, or its name, whatever its case. Refused when none or more than one does. */
fun pageNamed(n: Notebook, asked: String): NotebookPage {
    // By name first, as he and the board say them; a page's id only when no page has that name.
    val named = n.pages.filter { it.name.trim().equals(asked.trim(), ignoreCase = true) }
    if (named.isEmpty()) n.pages.firstOrNull { it.id == asked }?.let { return it }
    return named.singleOrNull() ?: refuse(
        if (named.isEmpty()) "No page is called \"$asked\". Pages: ${n.pages.joinToString(", ") { it.name }}. Nothing was changed."
        else "More than one page is called \"$asked\". Nothing was changed.",
    )
}

private fun pageOf(n: Notebook, key: String) = n.pages.firstOrNull { p -> p.items.any { it.key == key } }

private fun cellOf(n: Notebook, key: String) = n.pages.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.key == key }?.cell }

/** A module's title and its project, for saying what was done: "Needs you", "Handoff (soma)". */
fun moduleTitle(key: String): String = moduleRef(key)?.let { it.kind.title + (it.project?.let { p -> " ($p)" } ?: "") + (if (it.copy > 1) " #${it.copy}" else "") } ?: key

private fun cellWords(c: Cell) = "column ${c.x + 1}, row ${c.y + 1}, ${c.w}×${c.h}"

/** The cells taken on [page] by anything but [except], the pinned ones included. */
private fun taken(n: Notebook, page: String, except: String?, rules: ScreenRules): List<Cell> =
    n.pages.firstOrNull { it.id == page }?.items.orEmpty().filter { it.key != except }.mapNotNull { it.cell } + rules.pinned(n)

/** [w] by [h], and a little smaller: one cell less across, down, or both, never below one. */
private fun aLittleSmaller(w: Int, h: Int) = listOf(w to h, w - 1 to h, w to h - 1, w - 1 to h - 1).filter { it.first >= 1 && it.second >= 1 }.distinct()

/**
 * The free cell of [w] by [h] nearest what [where] asks on a page with [busy] taken, or null when the asked-for part of the
 * page has no room. [anchor] is the module a beside-spot is beside.
 */
private fun spotOn(busy: List<Cell>, columns: Int, rows: Int, w: Int, h: Int, where: Where, anchor: Cell?, strict: Boolean = true): Cell? {
    if (w > columns || h > rows) return null
    val midX = (columns - w) / 2
    val midY = (rows - h) / 2
    fun rowsMeet(c: Cell, k: Cell) = c.y < k.bottom && k.y < c.bottom
    fun colsMeet(c: Cell, k: Cell) = c.x < k.right && k.x < c.right
    val k = anchor
    // Each spot: which cells are in the asked-for part of the page, and how far one is from where it asks.
    fun inPart(c: Cell): Boolean = when (where.spot) {
        Spot.Any -> true
        Spot.TopLeft -> c.x <= midX && c.y <= midY
        Spot.TopRight -> c.x >= (columns - w + 1) / 2 && c.y <= midY
        Spot.BottomLeft -> c.x <= midX && c.y >= (rows - h + 1) / 2
        Spot.BottomRight -> c.x >= (columns - w + 1) / 2 && c.y >= (rows - h + 1) / 2
        Spot.Top -> c.y <= midY
        Spot.Bottom -> c.y >= (rows - h + 1) / 2
        Spot.RightOf -> k != null && c.x >= k.right && rowsMeet(c, k)
        Spot.LeftOf -> k != null && c.right <= k.x && rowsMeet(c, k)
        Spot.Below -> k != null && c.y >= k.bottom && colsMeet(c, k)
        Spot.Above -> k != null && c.bottom <= k.y && colsMeet(c, k)
    }
    fun far(c: Cell): Int = when (where.spot) {
        Spot.Any, Spot.Top -> c.y * columns + c.x
        Spot.TopLeft -> c.x + c.y
        Spot.TopRight -> (columns - w - c.x) + c.y
        Spot.BottomLeft -> c.x + (rows - h - c.y)
        Spot.BottomRight -> (columns - w - c.x) + (rows - h - c.y)
        Spot.Bottom -> (rows - h - c.y) * columns + c.x
        Spot.RightOf -> (c.x - k!!.right) + abs(c.y - k.y)
        Spot.LeftOf -> (k!!.x - c.right) + abs(c.y - k.y)
        Spot.Below -> (c.y - k!!.bottom) + abs(c.x - k.x)
        Spot.Above -> (k!!.y - c.bottom) + abs(c.x - k.x)
    }
    var best: Cell? = null
    for (y in 0..rows - h) for (x in 0..columns - w) {
        val c = Cell(x, y, w, h)
        if ((strict && !inPart(c)) || busy.any { it.overlaps(c) }) continue
        val b = best
        if (b == null || far(c) < far(b) || (far(c) == far(b) && (c.y < b.y || (c.y == b.y && c.x < b.x)))) best = c
    }
    return best
}

/** Where a module of [size] goes: the page it lands on, its cell, and the page made for it when none had room. */
private data class Landing(val page: String, val cell: Cell, val made: CanvasVerb.PageMake? = null)

/**
 * Semantic placement (PRD §5): on [page], the spot [where] asks for at [size], shrinking a little; then anywhere on that
 * page; then the next page with room; then a new page after the last, at the size that fits there. [moving] is the
 * module's own key, whose old cells don't count as taken.
 */
private fun land(n: Notebook, page: String, size: Pair<Int, Int>, where: Where, moving: String?, rules: ScreenRules): Landing {
    val anchor = where.of?.let { cellOf(n, it) }
    fun try_(on: String, w: Where, strict: Boolean = true): Cell? {
        val busy = taken(n, on, moving, rules)
        for ((sw, sh) in aLittleSmaller(size.first, size.second)) spotOn(busy, n.columns, n.rows, sw, sh, w, anchor, strict)?.let { return it }
        return null
    }
    try_(page, where)?.let { return Landing(page, it) }
    // Anywhere on that page, as near as there is room to what was asked (top left beside a chat that holds the corner).
    if (where.spot != Spot.Any) try_(page, if (anchor == null && where.spot.beside) Where() else where, strict = false)?.let { return Landing(page, it) }
    val after = n.pages.dropWhile { it.id != page }.drop(1)
    for (next in after) try_(next.id, Where())?.let { return Landing(next.id, it) }
    // A new page, after the last, named for the one asked for; there, whatever size fits.
    val id = rules.pageId(n)
    val base = n.pages.firstOrNull { it.id == page }?.name ?: "Page"
    val name = generateSequence(2) { it + 1 }.map { "$base $it" }.first { nm -> n.pages.none { it.name.equals(nm, ignoreCase = true) } }
    val busy = rules.pinned(n)
    for (sh in size.second downTo 1) for (sw in size.first downTo 1) {
        spotOn(busy, n.columns, n.rows, sw, sh, Where(), null)?.let { return Landing(id, it, CanvasVerb.PageMake(id, name, n.pages.lastOrNull()?.id)) }
    }
    refuse("No page here has room for it, not even a new one. Nothing was changed.")
}

/** The module [word] names on this canvas, as a key, refused when it is no module agents may arrange. */
private fun module(word: String, rules: ScreenRules): String {
    if (rules.widgets && WIDGET_FILE.matches(word.trim())) {
        if (!rules.widgetKnown(word.trim())) refuse("${word.trim()} isn't in the widget library. Nothing was changed.")
        return word.trim()
    }
    moduleKeyOf(word, chat = true)?.takeIf { it in rules.kept }?.let { refuse("${moduleTitle(it)} stays where it is. Nothing was changed.") }
    val key = moduleKeyOf(word) ?: refuse("\"$word\" is not a module. Modules: ${ModuleKind.entries.filter { !it.chatScreen }.joinToString(", ") { it.id + if (it.perProject) ":PROJECT" else "" }}. Nothing was changed.")
    if (key in rules.kept) refuse("${moduleTitle(key)} stays where it is. Nothing was changed.")
    return key
}

private fun onCanvas(n: Notebook, key: String): Pair<NotebookPage, Cell> {
    val page = pageOf(n, key) ?: refuse("${moduleTitle(key)} is not on this canvas. Nothing was changed.")
    return page to (page.items.first { it.key == key }.cell ?: refuse("${moduleTitle(key)} has no place yet. Nothing was changed."))
}

/** [ask] as exact verbs against [n] as it is now. Throws [AskRefused] with the reason when it can't be done; nothing is guessed. */
fun resolveAsk(n: Notebook, ask: CanvasAsk, rules: ScreenRules): Resolved = when (ask) {
    is CanvasAsk.PageMake -> {
        val name = pageNameOf(ask.name)
        if (n.pages.any { it.name.equals(name, ignoreCase = true) }) refuse("A page is already called \"$name\". Nothing was changed.")
        Resolved(listOf(CanvasVerb.PageMake(rules.pageId(n), name, n.pages.lastOrNull()?.id)), "made the page \"$name\"")
    }
    is CanvasAsk.PageRename -> {
        val page = pageNamed(n, ask.page)
        val name = pageNameOf(ask.name)
        if (n.pages.any { it.id != page.id && it.name.equals(name, ignoreCase = true) }) refuse("Another page is already called \"$name\". Nothing was changed.")
        Resolved(listOf(CanvasVerb.PageRename(page.id, name)), "renamed \"${page.name}\" to \"$name\"")
    }
    is CanvasAsk.PageRemove -> {
        val page = pageNamed(n, ask.page)
        if (page.id in rules.keptPages) refuse("\"${page.name}\" stays: it holds the chat. Nothing was changed.")
        if (n.pages.size <= 1) refuse("\"${page.name}\" is the only page. Nothing was changed.")
        // Only modules come back with an undo: a page with anything else on it is his to remove by hand.
        val others = page.items.filter { it.kind != ItemKind.Module }
        if (others.isNotEmpty()) refuse("\"${page.name}\" has apps, widgets or threads on it; only a page of modules is removed by an agent. Nothing was changed.")
        Resolved(listOf(CanvasVerb.PageRemove(page.id)), "removed the page \"${page.name}\"" + if (page.items.isEmpty()) "" else " and its ${page.items.size} modules")
    }
    is CanvasAsk.Place -> {
        val named = module(ask.module, rules)
        val ref = moduleRef(named)
        val asked = ask.page?.let { pageNamed(n, it).id }
        val besideKey = ask.where.of?.let { w -> (moduleKeyOf(w, chat = true) ?: refuse("\"$w\" is not a module. Nothing was changed.")).let { copyOf(n, w, it, asked) } }
        if (ask.where.spot.beside && besideKey == null) refuse("${ask.where.spot.word} needs a module to be beside. Nothing was changed.")
        val page = when {
            asked != null -> asked
            besideKey != null -> onCanvas(n, besideKey).first.id
            else -> pageOf(n, copyOf(n, ask.module, named, null))?.id ?: n.pages.firstOrNull()?.id ?: refuse("This canvas has no page yet. Nothing was changed.")
        }
        // The copy there already, or the module itself when it is nowhere, or a new copy for this page: putting it on a
        // page never takes it off another.
        val all = if (ref == null) emptyList() else copies(n, named)
        val key = when {
            ref == null || ref.copy > 1 || '#' in ask.module -> named
            else -> all.firstOrNull { it.second.id == page }?.first ?: if (all.isEmpty()) named
                else (2..99).map { ref.copy(copy = it).key }.firstOrNull { k -> all.none { it.first == k } } ?: refuse("${moduleTitle(named)} is on too many pages already. Nothing was changed.")
        }
        if (besideKey != null && pageOf(n, besideKey)?.id != page) refuse("${moduleTitle(besideKey)} is not on that page. Nothing was changed.")
        val size = ask.size?.cells(rules.span(n), n.rows) ?: cellOf(n, key)?.let { it.w to it.h } ?: (if (ref != null) rules.sizeOf(ref, n) else SizeWord.Card.cells(rules.span(n), n.rows))
        val at = land(n, page, size, Where(ask.where.spot, besideKey), moving = key, rules)
        val verbs = listOfNotNull(at.made) + CanvasVerb.Place(key, if (ref == null) ItemKind.Widget else ItemKind.Module, at.page, at.cell)
        val pageName = at.made?.name ?: n.pages.first { it.id == at.page }.name
        if (at.made == null && cellOf(n, key) == at.cell && pageOf(n, key)?.id == at.page) Resolved(emptyList(), "${moduleTitle(key)} stays where it is: it is already as near there as this page has room")
        else Resolved(verbs, (if (pageOf(n, key) != null) "moved " else "put ") + "${moduleTitle(key)} (${shortName(key)}) on \"$pageName\" at ${cellWords(at.cell)}" + (at.made?.let { " (a new page: the one asked for had no room)" } ?: ""))
    }
    is CanvasAsk.Move -> {
        // With copies on several pages, the one on the page it goes to is the one meant.
        val key = copyOf(n, ask.module, module(ask.module, rules), ask.page?.let { pageNamed(n, it).id })
        val (from, cell) = onCanvas(n, key)
        val besideKey = ask.where.of?.let { w -> (moduleKeyOf(w, chat = true) ?: refuse("\"$w\" is not a module. Nothing was changed.")).let { copyOf(n, w, it, ask.page?.let { p -> pageNamed(n, p).id } ?: from.id) } }
        if (ask.where.spot.beside && besideKey == null) refuse("${ask.where.spot.word} needs a module to be beside. Nothing was changed.")
        val page = ask.page?.let { pageNamed(n, it).id } ?: besideKey?.let { onCanvas(n, it).first.id } ?: from.id
        val at = land(n, page, cell.w to cell.h, Where(ask.where.spot, besideKey), moving = key, rules)
        val verbs = listOfNotNull(at.made) + CanvasVerb.Move(key, at.page, at.cell)
        if (at.made == null && at.page == from.id && at.cell == cell) return Resolved(emptyList(), "${moduleTitle(key)} stays where it is: it is already as near there as this page has room")
        Resolved(verbs, "moved ${moduleTitle(key)} to \"${at.made?.name ?: n.pages.first { it.id == at.page }.name}\" at ${cellWords(at.cell)}")
    }
    is CanvasAsk.Resize -> {
        val key = copyOf(n, ask.module, module(ask.module, rules), ask.page?.let { pageNamed(n, it).id })
        val (page, cell) = onCanvas(n, key)
        val (w, h) = ask.size.cells(rules.span(n), n.rows)
        val busy = taken(n, page.id, key, rules)
        val inPlace = Cell(cell.x, cell.y, w, h).takeIf { it.right <= n.columns && it.bottom <= n.rows && busy.none { b -> b.overlaps(it) } }
        // Not where it is: as near there as there is room on its page, a little smaller if need be; never onto another page.
        val near = inPlace ?: aLittleSmaller(w, h).firstNotNullOfOrNull { (sw, sh) ->
            (0..n.rows - sh).flatMap { y -> (0..n.columns - sw).map { x -> Cell(x, y, sw, sh) } }
                .filter { c -> busy.none { it.overlaps(c) } }
                .minByOrNull { abs(it.x - cell.x) + abs(it.y - cell.y) }
        } ?: refuse("\"${page.name}\" has no room for ${moduleTitle(key)} as a ${ask.size.word}. Nothing was changed.")
        Resolved(listOf(CanvasVerb.Move(key, page.id, near)), "made ${moduleTitle(key)} a ${ask.size.word} at ${cellWords(near)}")
    }
    is CanvasAsk.Remove -> {
        val key = copyOf(n, ask.module, module(ask.module, rules), ask.page?.let { pageNamed(n, it).id })
        val (page, _) = onCanvas(n, key)
        Resolved(listOf(CanvasVerb.Remove(key)), "took ${moduleTitle(key)} off \"${page.name}\"")
    }
}

/** What undoes [verb], from the canvas [before] it: a page made goes, a module put back where it was, a removed page comes back with its modules. */
fun inverseOf(before: Notebook, verb: CanvasVerb): List<CanvasVerb> = when (verb) {
    is CanvasVerb.PageMake -> listOf(CanvasVerb.PageRemove(verb.id))
    is CanvasVerb.PageRename -> before.pages.firstOrNull { it.id == verb.id }?.let { listOf(CanvasVerb.PageRename(verb.id, it.name)) }.orEmpty()
    is CanvasVerb.PageRemove -> {
        val at = before.pages.indexOfFirst { it.id == verb.id }
        if (at < 0) emptyList() else {
            val page = before.pages[at]
            listOf(CanvasVerb.PageMake(page.id, page.name, before.pages.getOrNull(at - 1)?.id ?: PAGE_START)) +
                page.items.mapNotNull { item -> item.cell?.let { CanvasVerb.Place(item.key, item.kind, page.id, it) } }
        }
    }
    is CanvasVerb.Place, is CanvasVerb.Move -> {
        val key = if (verb is CanvasVerb.Place) verb.key else (verb as CanvasVerb.Move).key
        val page = pageOf(before, key)
        val cell = cellOf(before, key)
        if (page == null || cell == null) listOf(CanvasVerb.Remove(key)) else listOf(CanvasVerb.Move(key, page.id, cell))
    }
    is CanvasVerb.Remove -> {
        val page = pageOf(before, verb.key)
        val item = page?.items?.first { it.key == verb.key }
        if (page == null || item?.cell == null) emptyList() else listOf(CanvasVerb.Place(item.key, item.kind, page.id, item.cell))
    }
    is CanvasVerb.Unknown -> emptyList()
}

/** The verbs that undo [verbs] applied in order from [before], in the order to apply them: the last change undone first. */
fun restorePoint(before: Notebook, verbs: List<CanvasVerb>): List<CanvasVerb> {
    var n = before
    val back = ArrayList<List<CanvasVerb>>()
    for (v in verbs) {
        back += inverseOf(n, v)
        n = applyVerb(n, v).notebook
    }
    return back.reversed().flatten()
}

/** What an undo did: the canvas after it, the verbs that applied, and what it left, in words. */
data class Undone(val notebook: Notebook, val applied: List<CanvasVerb>, val left: List<String>)

/**
 * [inverses] applied to [now] as far as they still apply (PRD §8: "the undo applies what still applies and says what it left").
 * A page is only taken away when nothing is left on it, so an undo never takes what someone put there since.
 */
fun undoOn(now: Notebook, inverses: List<CanvasVerb>): Undone {
    var n = now
    val applied = ArrayList<CanvasVerb>()
    val left = ArrayList<String>()
    for (v in inverses) {
        if (v is CanvasVerb.PageRemove) {
            val page = n.pages.firstOrNull { it.id == v.id }
            if (page != null && page.items.isNotEmpty()) { left += "the page \"${page.name}\" has things on it now, so it stays"; continue }
        }
        val r = applyVerb(n, v)
        if (r.applied) { applied += v; n = r.notebook } else left += what(v) + ": " + r.why
    }
    return Undone(n, applied, left)
}

private fun what(v: CanvasVerb): String = when (v) {
    is CanvasVerb.PageMake -> "the page \"${v.name}\""
    is CanvasVerb.PageRename -> "the page's old name \"${v.name}\""
    is CanvasVerb.PageRemove -> "taking a page away"
    is CanvasVerb.Place -> moduleTitle(v.key)
    is CanvasVerb.Move -> moduleTitle(v.key)
    is CanvasVerb.Remove -> "taking ${moduleTitle(v.key)} off"
    is CanvasVerb.Unknown -> "a newer build's change"
}

/** What an agent sees of the canvas before arranging it: each page, what is on it (modules by kind, size and cells), and its free room. */
fun boardJson(n: Notebook, rules: ScreenRules, only: String? = null): JSONObject {
    val pages = if (only == null) n.pages else listOf(pageNamed(n, only))
    return JSONObject().put("ok", true).put("columns", n.columns).put("rows", n.rows).put("pages", JSONArray(pages.map { p ->
        val busy = taken(n, p.id, null, rules)
        // What is pinned on every page (the desktop's chat) is on this page too, so an agent never looks for room under it.
        val pinned = rules.pinned(n).filter { c -> p.items.none { it.cell == c } }.map { c ->
            JSONObject().put("kind", "chat").put("name", "chat").put("size", "pinned").put("cells", JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
        }
        JSONObject().put("page", p.name).put("items", JSONArray(pinned + p.items.mapNotNull { item ->
            val c = item.cell ?: return@mapNotNull null
            val ref = moduleRef(item.key)
            JSONObject().put("kind", if (ref != null) MODULE_TILE_KIND else item.kind.name.lowercase())
                .put("name", if (ref != null) shortName(item.key) else item.key)
                .put("size", moduleSize(c.w, c.h, rules.span(n)).name.lowercase())
                .put("cells", JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
        })).put("free", (0 until n.rows).sumOf { y -> (0 until n.columns).count { x -> busy.none { it.overlaps(Cell(x, y, 1, 1)) } } })
    }))
}

/** What `canvas modules` says: each module an agent can place, its id, what it shows, and whether it needs a project. */
fun modulesJson(): JSONObject = JSONObject().put("ok", true).put("modules", JSONArray(ModuleKind.entries.filter { !it.chatScreen }.map {
    JSONObject().put("id", it.id + if (it.perProject) ":PROJECT" else "").put("title", it.title).put("shows", it.blurb)
})).put("sizes", JSONArray(SizeWord.entries.map { it.word })).put("where", JSONArray(Spot.entries.map { it.word + if (it.beside) " MODULE" else "" }))

/** A restore point: the request it undoes and the verbs that undo it, in the order to apply them. */
data class RestorePoint(val request: String, val inverses: List<CanvasVerb>)

/**
 * A screen's canvas as the agents' verbs reach it. Its owner keeps the canvas, the log and the restore points; everything
 * here is called on the thread that owns them.
 */
interface CanvasPad {
    val rules: ScreenRules

    fun notebook(): Notebook

    /** Applies [verbs], as part of [request] from [actor], in order; why the first that didn't apply didn't, or null when all did. */
    fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String?

    /** The newest agent request not yet undone, or null when there is none. */
    fun lastRestorePoint(): RestorePoint?

    /** Applies an undo's [verbs] for [restored], so it is not undone again; why it stopped partway, or null when all applied. */
    fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String?
}

/**
 * The words an agent says (`soma canvas place needs card top-left --page Soma`), as one call against [pad]. Every call of
 * one [request] is one restore point. Answers as JSON: ok, what was done ("did"), or the error and that nothing changed.
 */
object CanvasCommands {
    const val USAGE = "canvas board [PAGE] | modules | page-make NAME | page-rename NAME --to NEW | page-remove NAME | " +
        "place MODULE [SIZE] [WHERE [MODULE]] [--page NAME] | move MODULE WHERE [MODULE] [--page NAME] | resize MODULE SIZE [--page NAME] | " +
        "remove MODULE [--page NAME] | undo | templates | template-save NAME [--page PAGE] [--replace] | template-apply NAME [--page NEW | --onto PAGE] | " +
        "layout | remember PREFERENCE. On the car, which shows only its own page, a template goes onto that page. A module can be on several pages: placing it on another page adds a copy there (needs#2). " +
        "Read `layout` (LAYOUT.md, his stated preferences) before arranging; `remember` only what he says he prefers."

    /** [words] as an ask; throws [AskRefused] with the usage when they say none. */
    fun ask(words: List<String>): CanvasAsk {
        fun usage(): Nothing = refuse("Usage: $USAGE")
        val verb = words.firstOrNull() ?: usage()
        val rest = words.drop(1).toMutableList()
        fun option(name: String): String? {
            val at = rest.indexOf(name)
            if (at < 0) return null
            val value = rest.drop(at + 1).joinToString(" ").ifBlank { usage() }
            while (rest.size > at) rest.removeAt(at)
            return value
        }
        fun where(from: List<String>): Where {
            if (from.isEmpty()) return Where()
            val spot = Spot.of(from[0]) ?: refuse("\"${from[0]}\" is not a place on a page. Places: ${Spot.entries.joinToString(", ") { it.word }}. Nothing was changed.")
            if (spot.beside && from.size != 2) refuse("${spot.word} is said with one module after it. Nothing was changed.")
            if (!spot.beside && from.size != 1) usage()
            return Where(spot, from.getOrNull(1))
        }
        return when (verb) {
            "page-make" -> CanvasAsk.PageMake(rest.joinToString(" ").ifBlank { usage() })
            "page-rename" -> { val to = option("--to") ?: usage(); CanvasAsk.PageRename(rest.joinToString(" ").ifBlank { usage() }, to) }
            "page-remove" -> CanvasAsk.PageRemove(rest.joinToString(" ").ifBlank { usage() })
            "place" -> {
                val page = option("--page")
                val module = rest.removeFirstOrNull() ?: usage()
                val size = rest.firstOrNull()?.let { SizeWord.of(it) }?.also { rest.removeAt(0) }
                CanvasAsk.Place(module, size, where(rest), page)
            }
            "move" -> {
                val page = option("--page")
                val module = rest.removeFirstOrNull() ?: usage()
                if (rest.isEmpty() && page == null) usage()
                CanvasAsk.Move(module, where(rest), page)
            }
            "resize" -> {
                val page = option("--page")
                if (rest.size != 2) usage()
                CanvasAsk.Resize(rest[0], page = page, size = SizeWord.of(rest[1]) ?: refuse("\"${rest[1]}\" is not a size. Sizes: ${SizeWord.entries.joinToString(", ") { it.word }}. Nothing was changed."))
            }
            "remove" -> { val page = option("--page"); CanvasAsk.Remove(rest.singleOrNull() ?: usage(), page) }
            else -> usage()
        }
    }

    /** Runs [words] against [pad] as part of [request] from [actor] ("agent:front", "agent:<thread>"). */
    fun run(words: List<String>, pad: CanvasPad, request: String, actor: String, shelf: CanvasShelf? = null): JSONObject = try {
        templateWords(words, pad, shelf, request, actor) ?: when (words.firstOrNull()) {
            "board" -> boardJson(pad.notebook(), pad.rules, words.drop(1).joinToString(" ").ifBlank { null })
            "modules" -> modulesJson()
            "undo" -> {
                val point = pad.lastRestorePoint() ?: refuse("Nothing an agent did here is left to undo.")
                val done = undoOn(pad.notebook(), point.inverses)
                if (done.applied.isEmpty()) refuse(if (done.left.isEmpty()) "Nothing of it can be undone here. Nothing was changed." else "None of it could be undone: " + done.left.joinToString("; ") + ". Nothing was changed.")
                pad.undone(point, done.applied, actor)?.let { refuse("The undo stopped partway: $it. What it had put back stays; ask for undo again to try the rest.") }
                JSONObject().put("ok", true).put("did", "undid the last arrangement" + if (done.left.isEmpty()) "" else "; left: " + done.left.joinToString("; "))
            }
            else -> {
                val said = resolveAsk(pad.notebook(), ask(words), pad.rules)
                if (said.verbs.isNotEmpty()) pad.apply(said.verbs, request, actor)?.let { refuse(if (said.verbs.size == 1) "$it. Nothing was changed." else "It stopped partway: $it. What came before that stays, and undo takes it back.") }
                JSONObject().put("ok", true).put("did", said.said)
            }
        }
    } catch (e: AskRefused) {
        JSONObject().put("ok", false).put("error", e.message)
    }
}

/**
 * Restore points kept as JSON text, for a screen whose canvas isn't its verb log (the Fold, whose notebook is its own
 * document): oldest first. More verbs for the same request go in front of its earlier ones, so undo takes the newest back
 * first; an undone one is dropped. Text that can't be read is an empty list, never an error.
 */
class RestorePoints(private val read: () -> String?, private val write: (String) -> Unit) {
    fun all(): List<RestorePoint> = runCatching {
        JSONArray(read() ?: "[]").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val v = o.getJSONArray("inverses")
                RestorePoint(o.getString("request"), (0 until v.length()).map { VerbJson.verbOf(v.getJSONObject(it)) })
            }
        }
    }.getOrDefault(emptyList())

    private fun save(points: List<RestorePoint>) = write(JSONArray(points.map { p ->
        JSONObject().put("request", p.request).put("inverses", JSONArray(p.inverses.map { VerbJson.verb(it) }))
    }).toString())

    fun add(request: String, inverses: List<CanvasVerb>) {
        val points = all()
        val was = points.firstOrNull { it.request == request }
        save(points.filter { it.request != request } + RestorePoint(request, inverses + was?.inverses.orEmpty()))
    }

    fun last(): RestorePoint? = all().lastOrNull()

    fun drop(request: String) = save(all().filter { it.request != request })
}
