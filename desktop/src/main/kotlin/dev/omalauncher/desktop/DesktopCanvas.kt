package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.canvas.ModuleKind
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

/** The one page the window shows. */
const val DESKTOP_PAGE = "soma"

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
class DesktopCanvas(private val file: File, private val device: String) {
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

    /** What is on the page, the chat included. */
    fun items(): List<NotebookItem> = page.pages.firstOrNull { it.id == DESKTOP_PAGE }?.items.orEmpty()

    fun has(key: String) = items().any { it.key == key }

    /** The first cells of [size] nothing has, shrinking to fit when the page has no room at that size; null on a full page. */
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
        return done
    }

    @Synchronized private fun newRequest() = "r" + System.currentTimeMillis().toString(36) + "-" + (++requests).toString(36)

    /** Adds the module [key] in the first room on the page at its size here (smaller if that is all there is). */
    fun add(key: String): Applied {
        val ref = moduleRef(key) ?: return Applied(page, false, "no such module")
        if (has(key)) return Applied(page, false, "already here")
        val cell = spot(desktopSize(ref.kind)) ?: return Applied(page, false, "no room")
        return apply(CanvasVerb.Place(key, ItemKind.Module, DESKTOP_PAGE, cell))
    }

    private fun write(next: VerbLog) {
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(VerbJson.log(next))
        if (!fresh.renameTo(file)) { file.delete(); check(fresh.renameTo(file)) { "couldn't move ${fresh.name} over ${file.name}" } }
    }
}
