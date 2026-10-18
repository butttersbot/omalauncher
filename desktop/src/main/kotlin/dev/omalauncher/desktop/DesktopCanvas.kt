package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.canvas.ModuleKind
import dev.omalauncher.canvas.RestorePoint
import dev.omalauncher.canvas.ScreenRules
import dev.omalauncher.canvas.restorePoint
import dev.omalauncher.canvas.moduleKey
import dev.omalauncher.canvas.moduleRef
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.Applied
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.VerbJson
import dev.omalauncher.widgets.layout.VerbLog
import dev.omalauncher.widgets.layout.VerbOrigin
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.firstFit
import java.io.File

/*
 * Soma desktop's canvas (Soma Canvas slice 5): the window is a page of blocks on a grid, the chat one of them, the
 * modules the others. Its layout is this screen's alone (Boss, 2026-10-16: a size on one screen is not a size on
 * another) and is kept as verbs from the first day: the log is what is saved, and the page is the log replayed. A move
 * or a resize by hand is a verb like any other, kept for order and undo only: nothing learns from it.
 */

/** The desktop's grid: twelve columns across the window, eight rows down it. */
const val DESKTOP_COLUMNS = 12
const val DESKTOP_ROWS = 8

/** The first page, the chat's: the one the window starts on, never removed. */
const val DESKTOP_PAGE = "soma"

/** Ahead of an undo's request id: the request it undid follows. */
private const val UNDO = "undo:"

/** The chat's own block: always on the page, never removed (a link to a thread opens its tab there). */
val CHAT_BLOCK: String = moduleKey(ModuleKind.Chat)!!

/** A module's starting size here: the Fold's six-column size, a little wider and taller for the window's cells. */
fun desktopSize(kind: ModuleKind): Pair<Int, Int> = when (kind) {
    ModuleKind.Chat -> 5 to DESKTOP_ROWS
    else -> minOf(DESKTOP_COLUMNS, (kind.size.first * 4 + 2) / 3) to minOf(DESKTOP_ROWS, kind.size.second + 1)
}

/** The page a new Soma starts with: the chat on the left, what needs him and how things stand beside it. */
fun startingVerbs(): List<CanvasVerb> = listOf(
    CanvasVerb.PageMake(DESKTOP_PAGE, "Soma"),
    CanvasVerb.Place(CHAT_BLOCK, ItemKind.Module, DESKTOP_PAGE, Cell(0, 0, 5, DESKTOP_ROWS)),
    CanvasVerb.Place(moduleKey(ModuleKind.Needs)!!, ItemKind.Module, DESKTOP_PAGE, Cell(5, 0, 4, 3)),
    CanvasVerb.Place(moduleKey(ModuleKind.Threads)!!, ItemKind.Module, DESKTOP_PAGE, Cell(9, 0, 3, 3)),
    CanvasVerb.Place(moduleKey(ModuleKind.Timeline)!!, ItemKind.Module, DESKTOP_PAGE, Cell(5, 3, 7, 3)),
    CanvasVerb.Place(moduleKey(ModuleKind.Computers)!!, ItemKind.Module, DESKTOP_PAGE, Cell(5, 6, 4, 2)),
    CanvasVerb.Place(moduleKey(ModuleKind.NewThread)!!, ItemKind.Module, DESKTOP_PAGE, Cell(9, 6, 3, 1)),
)

/** An empty canvas, before any verb. */
fun emptyCanvas() = Notebook(rows = DESKTOP_ROWS, pages = emptyList(), columns = DESKTOP_COLUMNS)

/** [log] replayed onto an empty canvas: each verb that applies, in order. One that doesn't is kept in the log and skipped. */
fun replay(log: VerbLog): Notebook = log.entries.fold(emptyCanvas()) { notebook, entry -> applyVerb(notebook, entry.verb).notebook }

/**
 * The canvas as the window shows it and its log as kept in [file]. [device] names this machine in each verb's origin.
 * Every change goes through [apply]: applied to the page, appended to the log, and the log written, or none of it.
 */
class DesktopCanvas(private val file: File, private val device: String) : CanvasPad {
    var log: VerbLog by mutableStateOf(VerbLog(DESKTOP_PAGE))
        private set

    /** The page as the log has it now. */
    var page: Notebook by mutableStateOf(emptyCanvas())
        private set

    /** What a log that couldn't be read was set aside as, said once; null when nothing was. */
    var setAside: File? = null
        private set

    /**
     * Why the canvas isn't being kept, when it isn't: its file couldn't be written, or one that couldn't be read couldn't
     * be set aside (so it is never written over). The page still changes in the window; it is just not kept. Null when kept.
     */
    var unkept: String? by mutableStateOf(null)
        private set

    /** Never written from here on: an unreadable log that couldn't be set aside stays as it was. */
    private var readOnly = false

    /** One request per change, however many come in one millisecond. */
    private var requests = 0

    /**
     * Reads the log, or starts one: with no file, the starting page; with a file that can't be read, the file is set
     * aside first (never written over) and the starting page begins a new log. The chat's block is there either way.
     */
    fun load() {
        val text = runCatching { file.readText() }.getOrNull()
        val read = text?.let { VerbJson.logOf(it) }
        if (text != null && read == null) {
            val aside = File(file.parentFile, file.name + ".unreadable-" + System.currentTimeMillis())
            if (file.renameTo(aside)) {
                setAside = aside
            } else {
                readOnly = true
                unkept = "The canvas's saved layout couldn't be read or set aside, so it is left as it is and changes here aren't kept."
            }
        }
        log = read ?: VerbLog(DESKTOP_PAGE)
        page = replay(log)
        if (read == null) startingVerbs().forEach { apply(it, "soma", "start") }
        // A log that lost the chat (an older Soma's, or one edited by hand) gets it back in the first room there is.
        if (items().none { it.key == CHAT_BLOCK }) {
            if (page.pages.none { it.id == DESKTOP_PAGE }) apply(CanvasVerb.PageMake(DESKTOP_PAGE, "Soma"), "soma", "start")
            spot(desktopSize(ModuleKind.Chat))?.let { apply(CanvasVerb.Place(CHAT_BLOCK, ItemKind.Module, DESKTOP_PAGE, it), "soma", "start") }
        }
    }

    /**
     * The page the window shows (slice 6: an agent can make more, on his word). The chat is on the first page and drawn on
     * every page at its cells, which no page's blocks may take.
     */
    var shown: String by mutableStateOf(DESKTOP_PAGE)

    /** What is on the page the window shows: the chat, and that page's blocks. */
    fun items(): List<NotebookItem> = itemsOn(shown()).let { on -> if (shown() == DESKTOP_PAGE) on else chatItem()?.let { listOf(it) + on } ?: on }

    private fun itemsOn(id: String) = page.pages.firstOrNull { it.id == id }?.items.orEmpty()

    private fun chatItem() = itemsOn(DESKTOP_PAGE).firstOrNull { it.key == CHAT_BLOCK }

    /** The page shown, or the first when the one shown has gone. */
    private fun shown(): String = shown.takeIf { id -> page.pages.any { it.id == id } } ?: DESKTOP_PAGE

    /** Whether [key] is anywhere on the canvas. */
    fun has(key: String) = page.pages.any { p -> p.items.any { it.key == key } }

    /** The first cells of [size] nothing has on the page shown, shrinking to fit when there's no room at that size; null on a full page. */
    fun spot(size: Pair<Int, Int>): Cell? {
        val taken = items().mapNotNull { it.cell }
        for (h in size.second downTo 1) for (w in size.first downTo 1) firstFit(w, h, taken, DESKTOP_COLUMNS, DESKTOP_ROWS)?.let { return it }
        return null
    }

    /**
     * [verb] from [actor] ("hand", "soma", "agent:…"), as part of [request]: applied and kept, or, when it doesn't apply
     * (a taken spot, the chat's removal), nothing changed. The chat's block is never removed.
     */
    fun apply(verb: CanvasVerb, actor: String = "hand", request: String = newRequest()): Applied {
        if (verb is CanvasVerb.Remove && verb.key == CHAT_BLOCK) return Applied(page, false, "the chat stays")
        if (verb is CanvasVerb.PageRemove && verb.id == DESKTOP_PAGE) return Applied(page, false, "the chat's page stays")
        // The chat is drawn on every page: nothing on another page goes under it, and it goes over nothing on any page.
        val chat = chatItem()?.cell
        val clash = when (verb) {
            is CanvasVerb.Place -> verb.page != DESKTOP_PAGE && chat != null && verb.cell.overlaps(chat)
            is CanvasVerb.Move -> if (verb.key == CHAT_BLOCK) page.pages.any { p -> p.items.any { it.key != CHAT_BLOCK && it.cell?.overlaps(verb.cell) == true } }
                else verb.page != DESKTOP_PAGE && chat != null && verb.cell.overlaps(chat)
            else -> false
        }
        if (clash) return Applied(page, false, "that spot is under the chat")
        val done = applyVerb(page, verb)
        if (!done.applied) return done
        val next = log.append(request, VerbOrigin(device, actor), verb)
        // A change that can't be kept still shows: the window is never left without its chat for a disk that won't take a
        // write. That it isn't kept is said, once, until a write is kept again.
        if (!readOnly) {
            runCatching { write(next) }
                .onSuccess { unkept = null }
                .onFailure { unkept = "Changes to the canvas aren't being kept: ${it.message ?: it.javaClass.simpleName}" }
        }
        log = next
        page = done.notebook
        // The page shown gone (an agent removed it): the window shows the chat's page, so a hand drag lands somewhere real.
        if (page.pages.none { it.id == shown }) shown = DESKTOP_PAGE
        return done
    }

    @Synchronized private fun newRequest() = "r" + System.currentTimeMillis().toString(36) + "-" + (++requests).toString(36)

    /** Adds the module [key] in the first room on the page shown at its size here (smaller if that is all there is). */
    fun add(key: String): Applied {
        val ref = moduleRef(key) ?: return Applied(page, false, "no such module")
        if (has(key)) return Applied(page, false, "already here")
        val cell = spot(desktopSize(ref.kind)) ?: return Applied(page, false, "no room")
        return apply(CanvasVerb.Place(key, ItemKind.Module, shown(), cell))
    }

    // The agents' verbs (slice 6): `soma canvas`, from Soma's own front agent with the token it is given for one request.

    override val rules = ScreenRules(
        pinned = { n -> n.pages.firstOrNull { it.id == DESKTOP_PAGE }?.items?.firstOrNull { it.key == CHAT_BLOCK }?.cell?.let { listOf(it) }.orEmpty() },
        // Beside the chat: a full module is as wide as the page is beside it.
        span = { n -> DESKTOP_COLUMNS - (n.pages.firstOrNull { it.id == DESKTOP_PAGE }?.items?.firstOrNull { it.key == CHAT_BLOCK }?.cell?.w ?: 0) },
        kept = setOf(CHAT_BLOCK),
        keptPages = setOf(DESKTOP_PAGE),
        sizeOf = { ref, _ -> desktopSize(ref.kind) },
        pageId = { n -> generateSequence(2) { it + 1 }.map { "page$it" }.first { id -> n.pages.none { it.id == id } } },
    )

    override fun notebook(): Notebook = page

    /** Each verb applied and kept in turn; the window then shows the page the last one changed, so he sees what was done. */
    override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
        for (v in verbs) {
            val done = apply(v, actor, request)
            if (!done.applied) return done.why
            when (v) {
                is CanvasVerb.PageMake -> shown = v.id
                is CanvasVerb.Place -> shown = v.page
                is CanvasVerb.Move -> shown = v.page
                else -> {}
            }
        }
        return null
    }

    /**
     * The newest agent request not undone yet, and what undoes it: worked out from the log itself, the page as it was just
     * before the request's first verb replayed, so nothing besides the log is kept.
     */
    override fun lastRestorePoint(): RestorePoint? {
        // How far each request has been undone: its verbs after its last undo are still to undo (one request can arrange,
        // undo and arrange again).
        val undoneTo = log.entries.filter { it.request.startsWith(UNDO) }.groupBy { it.request.removePrefix(UNDO) }.mapValues { (_, e) -> e.maxOf { it.version } }
        fun left(e: dev.omalauncher.widgets.layout.LoggedVerb) = e.version > (undoneTo[e.request] ?: 0L)
        val request = log.entries.lastOrNull { it.origin.actor.startsWith("agent") && !it.request.startsWith(UNDO) && left(it) }?.request ?: return null
        val verbs = log.request(request).filter(::left)
        val before = replay(log.copy(entries = log.entries.filter { it.version < verbs.first().version }))
        return RestorePoint(request, restorePoint(before, verbs.map { it.verb }))
    }

    override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? {
        val why = apply(verbs, UNDO + restored.request, actor)
        if (page.pages.none { it.id == shown }) shown = DESKTOP_PAGE
        return why
    }

    // Sync (slice 8b): this window's layout kept on the service as its own screen, never mirrored anywhere else.

    /** How far this log is the service's own: entries after it were made here and not yet sent. */
    @Volatile var syncedTo: Long = 0L
        private set

    /**
     * Where this window's log and the service's [synced] copy part, at start: the entries they share are the service's,
     * everything after is this window's own and still to send.
     */
    fun resumeSync(synced: VerbLog) {
        val shared = log.entries.zip(synced.entries).takeWhile { (mine, theirs) -> mine.version == theirs.version && mine.request == theirs.request && mine.verb == theirs.verb }
        syncedTo = shared.lastOrNull()?.first?.version ?: 0L
    }

    /** What changed here since the last round, as verbs to send, made on [base] (the service's version this window has). */
    fun pendingSync(base: Long): List<dev.omalauncher.canvas.SentVerb> =
        log.entries.filter { it.version > syncedTo }.map { dev.omalauncher.canvas.SentVerb(base, it.request, it.origin, it.verb, it.schema) }

    /**
     * The service's [canonical] log becomes this window's, with anything made here since [sentUpTo] (while the round was
     * on its way) applied again on top, to go in the next round. Kept like any change.
     */
    fun adopt(canonical: VerbLog, sentUpTo: Long) {
        val since = log.entries.filter { it.version > sentUpTo }
        var next = canonical.copy(screen = DESKTOP_PAGE)
        var shownPage = replay(next)
        val adopted = next.version
        for (e in since) {
            val done = applyVerb(shownPage, e.verb)
            if (done.applied) { next = next.append(e.request, e.origin, e.verb); shownPage = done.notebook }
        }
        if (!readOnly) runCatching { write(next) }.onFailure { unkept = "Changes to the canvas aren't being kept: ${it.message ?: it.javaClass.simpleName}" }
        log = next
        page = shownPage
        syncedTo = adopted
        if (page.pages.none { it.id == shown }) shown = DESKTOP_PAGE
    }

    private fun write(next: VerbLog) {
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(VerbJson.log(next))
        if (!fresh.renameTo(file)) { file.delete(); check(fresh.renameTo(file)) { "couldn't move ${fresh.name} over ${file.name}" } }
    }
}
