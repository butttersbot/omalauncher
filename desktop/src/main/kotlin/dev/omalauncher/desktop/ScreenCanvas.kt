package dev.omalauncher.desktop

import dev.omalauncher.canvas.CanvasPad
import dev.omalauncher.canvas.RestorePoint
import dev.omalauncher.canvas.ScreenRules
import dev.omalauncher.canvas.restorePoint
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.LoggedVerb
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.VerbJson
import dev.omalauncher.widgets.layout.VerbLog
import dev.omalauncher.widgets.layout.VerbOrigin
import dev.omalauncher.widgets.layout.applyVerb
import java.io.File

/*
 * A screen the service keeps (Soma Canvas slice 8a): the car's, for now, and any other the service draws with nobody at
 * it. Like the desktop window's canvas it is its verb log, replayed (PRD §8: sync is verbs, never documents), and agents
 * arrange it through the same words; unlike the window it has no chat, and its grid is its own. One per file in
 * ~/.config/soma/screens, written whole each change; one that can't be read is set aside, never written over.
 */
class ScreenCanvas(
    private val file: File, val screen: String, val columns: Int, val rows: Int, private val device: String,
    /** Whether the library has the widget file a `place NAME.owl` names: one it hasn't is refused, not placed empty. */
    private val widgetKnown: (String) -> Boolean = { true },
) : CanvasPad {
    @Volatile var log: VerbLog = VerbLog(screen)
        private set

    @Volatile var page: Notebook = Notebook(rows = rows, pages = emptyList(), columns = columns)
        private set

    private var requests = 0

    @Synchronized fun load() {
        val text = runCatching { file.readText() }.getOrNull()
        val read = text?.let { VerbJson.logOf(it) }
        if (text != null && read == null) file.renameTo(File(file.parentFile, file.name + ".unreadable-" + System.currentTimeMillis()))
        log = read ?: VerbLog(screen)
        page = replayOn(log)
        // A screen starts with one page, so there is somewhere to put the first thing.
        if (page.pages.isEmpty()) apply(CanvasVerb.PageMake("main", screen.replaceFirstChar { it.uppercase() }), "soma", "start")
    }

    private fun replayOn(log: VerbLog) = log.entries.fold(Notebook(rows = rows, pages = emptyList(), columns = columns)) { n, e -> applyVerb(n, e.verb).notebook }

    /** [verb] from [actor] as part of [request], applied and kept, or nothing changed and why. */
    @Synchronized fun apply(verb: CanvasVerb, actor: String = "hand", request: String = newRequest()): String? {
        val done = applyVerb(page, verb)
        if (!done.applied) return done.why
        val next = log.append(request, VerbOrigin(device, actor), verb)
        file.parentFile?.mkdirs()
        val fresh = File(file.parentFile, "." + file.name + ".new")
        fresh.writeText(VerbJson.log(next))
        if (!fresh.renameTo(file)) { file.delete(); check(fresh.renameTo(file)) { "couldn't keep ${file.name}" } }
        log = next
        page = done.notebook
        return null
    }

    @Synchronized private fun newRequest() = "r" + System.currentTimeMillis().toString(36) + "-" + (++requests).toString(36)

    override val rules = ScreenRules(
        // A module's six-column size, a third bigger on a wider grid, as the desktop's.
        sizeOf = { ref, n -> if (n.columns <= 6) ref.kind.size.first.coerceAtMost(n.columns) to ref.kind.size.second.coerceAtMost(n.rows)
            else minOf(n.columns, (ref.kind.size.first * 4 + 2) / 3) to minOf(n.rows, ref.kind.size.second + 1) },
        pageId = { n -> generateSequence(2) { it + 1 }.map { "page$it" }.first { id -> n.pages.none { it.id == id } } },
        // The shared library's widgets go on the service's screens by file name; each waits for his OK here before it runs.
        widgets = true,
        widgetKnown = widgetKnown,
        // The car shows its first page and nothing else.
        shownPage = if (screen == CAR_SCREEN) { n -> n.pages.firstOrNull()?.name } else null,
    )

    override fun notebook(): Notebook = page

    @Synchronized override fun apply(verbs: List<CanvasVerb>, request: String, actor: String): String? {
        for (v in verbs) apply(v, actor, request)?.let { return it }
        return null
    }

    @Synchronized override fun lastRestorePoint(): RestorePoint? {
        val undoneTo = log.entries.filter { it.request.startsWith(UNDO) }.groupBy { it.request.removePrefix(UNDO) }.mapValues { (_, e) -> e.maxOf { it.version } }
        fun left(e: LoggedVerb) = e.version > (undoneTo[e.request] ?: 0L)
        val request = log.entries.lastOrNull { it.origin.actor.startsWith("agent") && !it.request.startsWith(UNDO) && left(it) }?.request ?: return null
        val verbs = log.request(request).filter(::left)
        val before = replayOn(log.copy(entries = log.entries.filter { it.version < verbs.first().version }))
        return RestorePoint(request, restorePoint(before, verbs.map { it.verb }))
    }

    @Synchronized override fun undone(restored: RestorePoint, verbs: List<CanvasVerb>, actor: String): String? = apply(verbs, UNDO + restored.request, actor)

    companion object {
        private const val UNDO = "undo:"
    }
}
