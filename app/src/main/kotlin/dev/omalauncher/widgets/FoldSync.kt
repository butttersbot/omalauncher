package dev.omalauncher.widgets

import dev.omalauncher.canvas.ScreenSync
import dev.omalauncher.canvas.SentVerb
import dev.omalauncher.canvas.diffToVerbs
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.VerbOrigin
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.shown
import org.json.JSONObject

/*
 * The Fold's side of sync (Soma Canvas slice 8b, PRD §8). Sync never writes the notebook preference: what changed on home
 * since the last round is sent as verbs (worked out from home as it was then and as it is now, so the launcher's code that
 * changes home is untouched), and the canonical layout that comes back is brought about through LiveNotebook, home's only
 * writer, one exact change at a time. Its own changes come back in their canonical form, so they are never applied twice;
 * the launcher's own thread tiles go out as the launcher's. A home that was set aside or read only in part (not kept) takes
 * no part: it neither sends nor takes, so a re-migrated older home never goes over the service's copy. Apps and folders are
 * moved to where the canonical copy has them, never made or taken off by sync. Main thread only.
 */
class FoldSync(
    private val notebook: LiveNotebook,
    /** This screen on the service: one per phone. */
    screen: String,
    /** What the service gave this screen so far, kept between starts; written after each round. */
    private val load: () -> String?,
    private val keep: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Whether a page has his notes, stickers or ink on it (or isn't read yet): such a page is never taken away by sync, as
     * by the canvas command (round-2 review: an agent could erase his handwriting on a page with no tiles).
     */
    private val marked: (String) -> Boolean = { false },
) {
    private val screenName = screen

    /** Made the first time home is open, so its grid has home's real rows (round-1 review: read before open, it had the default). */
    val sync: ScreenSync by lazy {
        check(notebook.opened) { "home isn't open yet" }
        ScreenSync(screenName, dev.omalauncher.widgets.GRID_COLUMNS, notebook.rows,
            load()?.let { dev.omalauncher.widgets.layout.VerbJson.logOf(it) } ?: dev.omalauncher.widgets.layout.VerbLog(screenName))
    }

    /**
     * Home as sync sees it: the grid's columns, an app or a folder as the one cell it shows in, and only what has a cell. An
     * item still waiting for one (a widget file adopted for a page not drawn yet, one an agent is writing) is home's own
     * business until it lands: sync neither sends it nor ever takes it away (round-1 review: it was forgotten).
     */
    fun home(): Notebook {
        val n = notebook.snapshot()
        return n.copy(columns = dev.omalauncher.widgets.GRID_COLUMNS, pages = n.pages.map { p ->
            p.copy(items = p.items.filter { it.cell != null }.map { it.copy(cell = it.cell?.shown(it.kind != ItemKind.App && it.kind != ItemKind.Folder)) })
        })
    }

    /** Whether home takes part: read whole and kept this start. */
    fun active() = notebook.opened && notebook.kept

    /** Home as it was when the last [pending] was worked out: what the answer is the change from. */
    private var sentFrom: Notebook? = null

    /** What changed here since the last round, as verbs to send: a thread tile's as the launcher's, everything else as his. */
    fun pending(): List<SentVerb> {
        if (!notebook.opened || !notebook.kept) return emptyList()
        val request = "fold-" + now().toString(36)
        val here = home()
        sentFrom = here
        return diffToVerbs(sync.syncedPage, here).map { v ->
            val key = (v as? CanvasVerb.Place)?.key ?: (v as? CanvasVerb.Move)?.key ?: (v as? CanvasVerb.Remove)?.key
            val tile = key != null && here.pages.flatMap { it.items }.plus(sync.syncedPage.pages.flatMap { it.items }).any { it.key == key && it.kind == ItemKind.Thread }
            SentVerb(sync.synced.version, request, VerbOrigin("fold", if (tile) "launcher" else "hand"), v)
        }
    }

    /**
     * Brings home to [target], the canonical layout: each change it needs, done through the notebook. Apps and folders are
     * only moved (sync never pins or unpins one); a page with anything of his still on it isn't taken away. What couldn't be
     * done is said.
     */
    fun bringTo(target: Notebook): List<String> {
        if (!notebook.opened || !notebook.kept) return listOf("home isn't being kept this start, so nothing came in")
        val left = ArrayList<String>()
        // The change from home as it was sent to what came back, done to home as it is now: anything he changed while the
        // round was on its way stays, and goes in the next round.
        // Passes until nothing more applies: a place into cells something else is about to leave waits for it to go.
        var todo = diffToVerbs(sentFrom ?: home(), target)
        while (todo.isNotEmpty()) {
            val waiting = ArrayList<CanvasVerb>()
            for (v in todo) if (!doOne(v)) waiting += v
            if (waiting.size == todo.size) {
                // Nothing more applies one at a time: things that change places with each other (a swap, a ring). The layout
                // that came back is whole and has no overlaps, so its moves are done together; what still can't be is said.
                // Only a set that blocks itself alone (round-2 review: a move blocked by something he put there during the round
                // was forced over it): anything whose cells hold something outside the set waits for the next round.
                var together = waiting.filter { it is CanvasVerb.Move || (it is CanvasVerb.Place && it.kind != ItemKind.App && it.kind != ItemKind.Folder) }
                while (true) {
                    val keys = together.mapNotNull { keyOf(it) }.toSet()
                    val now = home()
                    val alone = together.filter { v ->
                        val (key, page, cell) = target(v) ?: return@filter false
                        val to = now.pages.firstOrNull { it.id == page } ?: return@filter false
                        // A move of something no longer on home is not made (round-3 review: it brought back what he removed).
                        if (v is CanvasVerb.Move && now.pages.none { p -> p.items.any { it.key == key } }) return@filter false
                        cell.x >= 0 && cell.y >= 0 && cell.right <= now.columns && cell.bottom <= now.rows &&
                            to.items.none { it.key != key && it.key !in keys && it.cell?.overlaps(cell) == true }
                    }
                    if (alone.size == together.size) break
                    together = alone
                }
                for (v in together) {
                    val (key, page, cell) = target(v) ?: continue
                    notebook.move(key, page); notebook.setCell(key, cell)
                }
                if (together.isNotEmpty()) notebook.save()
                (waiting - together.toSet()).forEach { left += what(it) + ": " + applyVerb(home(), it).why }
                break
            }
            todo = waiting
        }
        return left
    }

    private fun keyOf(v: CanvasVerb) = (v as? CanvasVerb.Move)?.key ?: (v as? CanvasVerb.Place)?.key

    private fun target(v: CanvasVerb) = when (v) {
        is CanvasVerb.Move -> Triple(v.key, v.page, v.cell)
        is CanvasVerb.Place -> Triple(v.key, v.page, v.cell)
        else -> null
    }

    /** [v] done through the notebook, when it applies to home as it is now; false when it doesn't (yet). */
    private fun doOne(v: CanvasVerb): Boolean {
        val check = applyVerb(home(), v)
        return when (v) {
            is CanvasVerb.PageMake -> notebook.addPageWithId(v.id, v.name, v.after)
            is CanvasVerb.PageRename -> notebook.renamePage(v.id, v.name)
            // A page goes only once nothing of his is left on it: no tiles, and no notes, stickers or ink.
            is CanvasVerb.PageRemove -> home().pages.firstOrNull { it.id == v.id }?.items?.isEmpty() == true && !marked(v.id) && notebook.withdrawPage(v.id)
            // Apps and folders are never made by sync.
            is CanvasVerb.Place -> if (v.kind == ItemKind.App || v.kind == ItemKind.Folder) true
                else check.applied.also { if (it) { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() } }
            is CanvasVerb.Move -> check.applied.also { if (it) { notebook.move(v.key, v.page); notebook.setCell(v.key, v.cell); notebook.save() } }
            is CanvasVerb.Remove -> {
                val kind = home().pages.flatMap { it.items }.firstOrNull { it.key == v.key }?.kind
                // Nor are they unpinned by it.
                if (kind != ItemKind.App && kind != ItemKind.Folder) notebook.forget(v.key)
                true
            }
            is CanvasVerb.Unknown -> true
        }
    }

    private fun what(v: CanvasVerb) = when (v) {
        is CanvasVerb.PageMake -> "the page \"${v.name}\""
        is CanvasVerb.PageRemove -> "a page stays: it has things, notes or ink on it here"
        is CanvasVerb.Place -> v.key
        is CanvasVerb.Move -> v.key
        else -> v.toString()
    }

    /**
     * One round, on the main thread except [exchange] (the network): what changed here is sent, and home is brought to what
     * comes back. Null when the service couldn't be reached: home keeps working on its own and the next round sends again.
     */
    fun round(exchange: (Long, List<SentVerb>) -> JSONObject?): List<String>? {
        if (!notebook.opened || !notebook.kept) return null
        val (target, notes) = sync.round(pending(), exchange) ?: return null
        return notes + finish(target)
    }

    /**
     * The end of a round whose network part ran elsewhere: home brought to [target], and what the service gave kept. A
     * refused round (no target) changes nothing on home: what was to be sent is worked out again next round.
     */
    fun finish(target: Notebook?): List<String> {
        if (target == null) { sentFrom = null; return emptyList() }
        val left = bringTo(target)
        sentFrom = null
        keep(dev.omalauncher.widgets.layout.VerbJson.log(sync.synced))
        return left
    }
}

/**
 * Sync on the phone, every half minute while the app runs: with the canvas service one of its Macs' runners advertises,
 * the phone's own key for that runner. Home's part on the main thread, the network off it. With no service anywhere,
 * nothing happens.
 */
object FoldSyncJob {
    @Volatile private var started = false
    private var lastSaid: List<String> = emptyList()

    fun start(app: dev.omalauncher.App) {
        if (started) return
        started = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val prefs = app.getSharedPreferences("canvas", android.content.Context.MODE_PRIVATE)
        val screen = "fold-" + (android.os.Build.DEVICE ?: "phone").lowercase().replace(Regex("[^a-z0-9-]"), "-").take(30)
        // Made on the main thread: the app's runtime is built there, never on this one (round-1 review).
        var made: FoldSync? = null
        fun <T> onMain(work: () -> T): T? {
            val done = java.util.concurrent.CountDownLatch(1)
            var got: T? = null
            main.post { got = runCatching(work).getOrNull(); done.countDown() }
            done.await(10, java.util.concurrent.TimeUnit.SECONDS)
            return got
        }
        kotlin.concurrent.thread(name = "canvas-sync", isDaemon = true) {
            while (true) {
                runCatching {
                    val config = onMain { app.runtime.proot.launcherConfig } ?: return@runCatching
                    val machines = dev.omalauncher.assist.readMacMachines(config)
                    val (service, key) = dev.omalauncher.canvas.findSomaService(machines) ?: return@runCatching
                    // A home set aside or read only in part takes no part at all: nothing kept now would match it later.
                    val fold = onMain {
                        made ?: FoldSync(app.runtime.widgets.notebook, screen, { prefs.getString("synced-$screen", null) }, { prefs.edit().putString("synced-$screen", it).apply() },
                            // As the canvas command asks: a page with his marks, or not read yet, is never taken away.
                            marked = { page ->
                                val book = dev.omalauncher.widgets.annotations.HomeAnnotations.of(app)
                                book.whenRead(page) {}
                                book.status(page) != dev.omalauncher.widgets.annotations.AnnotationBook.Status.Ready || !book.on(page).isEmpty
                            }).also { made = it }
                    } ?: return@runCatching
                    if (onMain { fold.active() } != true) return@runCatching
                    val pending = onMain { fold.pending() } ?: return@runCatching
                    val sync = onMain { fold.sync } ?: return@runCatching
                    val (target, notes) = sync.round(pending) { since, sent -> dev.omalauncher.canvas.syncExchange(service, key, screen, GRID_COLUMNS, sync.rows, since, sent) } ?: return@runCatching
                    val left = onMain { fold.finish(target) }.orEmpty()
                    // Said in the shade, once for as long as it stays the same (home itself has no place for it).
                    val said = notes + left
                    if (said != lastSaid) {
                        lastSaid = said
                        dev.omalauncher.assist.AgentNotices.canvasSync(app, said)
                    }
                }
                Thread.sleep(30_000)
            }
        }
    }
}
