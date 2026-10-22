package dev.omalauncher.canvas

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.layout.CanvasVerb
import dev.omalauncher.widgets.layout.ItemKind
import dev.omalauncher.widgets.layout.LoggedVerb
import dev.omalauncher.widgets.layout.Notebook
import dev.omalauncher.widgets.layout.NotebookItem
import dev.omalauncher.widgets.layout.VERB_SCHEMA
import dev.omalauncher.widgets.layout.VerbLog
import dev.omalauncher.widgets.layout.VerbOrigin
import dev.omalauncher.widgets.layout.applyVerb
import dev.omalauncher.widgets.layout.firstFit

/*
 * Sync (Soma Canvas slice 8b, PRD §8): verbs, never documents. Each screen has one log, and the service's order is the
 * canonical one. A screen sends what changed on it as verbs, each tagged with the version it was made on ([SentVerb.base]:
 * how a late page removal knows it may take things put there since, and says so); the service
 * appends them after everything it already has, re-resolving one that arrives late and no longer fits: a hand verb lands
 * as near its exact cells as there is room; an agent's goes where its words now say; a verb for a page that has gone is
 * dropped, and the sender is told. A verb from a newer schema is kept in the log and never applied. Nothing here learns
 * anything from the log: it is for order and undo only.
 */

/** A verb a screen sends: made on [base] (the screen's version when it was made), as part of [request], from [origin]. */
data class SentVerb(val base: Long, val request: String, val origin: VerbOrigin, val verb: CanvasVerb, val schema: Int = VERB_SCHEMA)

/** What the service did with a sent verb: appended as [version] (possibly re-resolved as [verb]), or dropped and [why]. */
data class Took(val version: Long?, val verb: CanvasVerb?, val why: String? = null)

/** [log] replayed on an empty notebook of [columns] by [rows]: each verb that applies, in order; one that doesn't is skipped. */
fun replayLog(log: VerbLog, columns: Int, rows: Int): Notebook =
    log.entries.fold(Notebook(rows = rows, pages = emptyList(), columns = columns)) { n, e -> if (e.schema > VERB_SCHEMA) n else applyVerb(n, e.verb, bare = true).notebook }

/** Whether a verb removes the page [id] (or the item [key]): what a late removal takes with it is said. */
private fun itemsOn(n: Notebook, id: String) = n.pages.firstOrNull { it.id == id }?.items.orEmpty()

/**
 * [sent] appended to [log] in the order given, after everything already there: the canonical order. One made on the
 * newest version applies as sent. One made earlier (late: a phone that was offline) applies as sent if it still can;
 * otherwise a move or a place is re-resolved to the nearest free cells of its size on its page (a page that has gone
 * drops it), and anything else that no longer applies is dropped and said. A newer schema's verb is kept, unapplied.
 */
fun appendSent(log: VerbLog, sent: List<SentVerb>, columns: Int, rows: Int): Pair<VerbLog, List<Took>> {
    var now = log
    var page = replayLog(log, columns, rows)
    val took = ArrayList<Took>()
    for (s in sent) {
        if (s.schema > VERB_SCHEMA || s.verb is CanvasVerb.Unknown) {
            now = now.copy(entries = now.entries + LoggedVerb(now.version + 1, s.request, s.origin, s.verb, s.schema))
            took += Took(now.version, s.verb, "kept for a newer build, not applied here")
            continue
        }
        // A late page removal (made before things were put there since) still goes, but what it takes is said.
        val taken = if (s.verb is CanvasVerb.PageRemove && s.base < now.version) itemsOn(page, s.verb.id).size else 0
        val exact = applyVerb(page, s.verb, bare = true)
        val verb = if (exact.applied) s.verb else reResolve(page, s.verb)
        if (verb == null) {
            took += Took(null, null, "dropped: ${exact.why}")
            continue
        }
        page = applyVerb(page, verb, bare = true).notebook
        now = now.append(s.request, s.origin, verb)
        took += Took(now.version, verb, when {
            verb != s.verb -> "placed as near as there was room: its cells were taken"
            taken > 0 -> "a page removed while away took $taken thing(s) put on it since"
            else -> null
        })
    }
    return now to took
}

/** A move or place whose cells are taken, at the nearest free cells of the same size on its page; null when it can't be. */
private fun reResolve(n: Notebook, verb: CanvasVerb): CanvasVerb? {
    val (key, pageId, cell) = when (verb) {
        is CanvasVerb.Place -> Triple(verb.key, verb.page, verb.cell)
        is CanvasVerb.Move -> Triple(verb.key, verb.page, verb.cell)
        else -> return null
    }
    val page = n.pages.firstOrNull { it.id == pageId } ?: return null
    if (verb is CanvasVerb.Move && n.pages.none { p -> p.items.any { it.key == key } }) return null
    val busy = page.items.filter { it.key != key }.mapNotNull { it.cell }
    val near = (0..n.rows - cell.h).flatMap { y -> (0..n.columns - cell.w).map { x -> Cell(x, y, cell.w, cell.h) } }
        .filter { c -> busy.none { it.overlaps(c) } }
        .minByOrNull { kotlin.math.abs(it.x - cell.x) + kotlin.math.abs(it.y - cell.y) }
        ?: firstFit(cell.w, cell.h, busy, n.columns, n.rows) ?: return null
    return when (verb) {
        is CanvasVerb.Place -> verb.copy(cell = near)
        is CanvasVerb.Move -> verb.copy(cell = near)
        else -> null
    }
}

/**
 * What changed from [before] to [after], as verbs (a screen whose own writer isn't a verb log, the Fold, says what a hand
 * change did this way): pages made, renamed and removed; items placed, moved or resized, and removed. Apps and folders
 * are sent as the cells they take (the service's copy holds them bare); a screen applying them back never makes one.
 */
fun diffToVerbs(before: Notebook, after: Notebook): List<CanvasVerb> {
    val verbs = ArrayList<CanvasVerb>()
    val beforePages = before.pages.associateBy { it.id }
    after.pages.forEachIndexed { i, p ->
        val was = beforePages[p.id]
        if (was == null) verbs += CanvasVerb.PageMake(p.id, p.name, after.pages.getOrNull(i - 1)?.id ?: dev.omalauncher.widgets.layout.PAGE_START)
        else if (was.name != p.name) verbs += CanvasVerb.PageRename(p.id, p.name)
    }
    fun where(n: Notebook) = n.pages.flatMap { p -> p.items.map { it.key to (p.id to it) } }.toMap()
    val old = where(before)
    val new = where(after)
    // Removals first, so a place into cells something left is never refused for them.
    for ((key, _) in old) if (key !in new) verbs += CanvasVerb.Remove(key)
    // A panel page's own items change cells together, in one verb with its layout (PanelVerbs.kt): a refit or a swap of
    // panels, done one move at a time, would pass through cells two items hold, and the service would put one elsewhere.
    val panels = after.pages.associate { p -> p.id to LinkedHashMap<String, Cell>() }
        .filterKeys { id -> after.pages.first { it.id == id }.panels.let { it != null || it != beforePages[id]?.panels } }
    val moves = ArrayList<CanvasVerb>()
    for ((key, at) in new) {
        val (pageId, item) = at
        val cell = item.cell ?: continue
        val prev = old[key]
        when {
            // An app or a folder goes as the cells it takes: the service's copy holds it bare, for room, never to open.
            prev == null -> moves += CanvasVerb.Place(key, item.kind, pageId, cell)
            prev.first == pageId && prev.second.cell != null && prev.second.cell != cell && pageId in panels -> panels.getValue(pageId)[key] = cell
            prev.first != pageId || prev.second.cell != cell -> moves += CanvasVerb.Move(key, pageId, cell)
        }
    }
    val recut = panels.filter { (id, cells) -> cells.isNotEmpty() || after.pages.first { it.id == id }.panels != beforePages[id]?.panels }
    // A page is re-cut once what leaves it for another page has gone, and what comes onto it comes after the cut: in passes,
    // each doing what waits on nothing left. A ring that waits on itself (things trading pages both ways) can't be one verb
    // per page (critic, 2026-10-19: a cut dropped whole undid all of his drags): its cells go as moves, each when it can
    // land exactly, then the pages' layouts. Only a straight swap between pages is left to the service to settle, as near
    // as there is room, as it always was.
    fun into(v: CanvasVerb) = (v as? CanvasVerb.Move)?.page ?: (v as? CanvasVerb.Place)?.page
    fun from(v: CanvasVerb) = (v as? CanvasVerb.Move)?.let { m -> old[m.key]?.first?.takeIf { it != m.page } }
    val cuts = recut.keys.toMutableList()
    val waiting = moves.toMutableList()
    while (cuts.isNotEmpty() || waiting.isNotEmpty()) {
        val ready = cuts.filter { id -> waiting.none { from(it) == id } }
        val going = waiting.filter { into(it) !in cuts }
        val stuck = ready.isEmpty() && going.isEmpty()
        if (stuck) {
            val rest = (waiting + cuts.flatMap { id -> recut.getValue(id).map { (key, cell) -> CanvasVerb.Move(key, id, cell) } }).toMutableList()
            var now = verbs.fold(before) { n, v -> applyVerb(n, v, bare = true).notebook }
            while (true) {
                val next = rest.firstOrNull { applyVerb(now, it, bare = true).applied } ?: break
                verbs += next
                now = applyVerb(now, next, bare = true).notebook
                rest -= next
            }
            verbs += rest
            verbs += cuts.map { id -> CanvasVerb.PagePanels(id, after.pages.first { it.id == id }.panels) }
            break
        }
        for (id in ready) { verbs += CanvasVerb.PagePanels(id, after.pages.first { it.id == id }.panels, recut.getValue(id)); cuts -= id }
        verbs += going
        waiting -= going.toSet()
    }
    for (id in beforePages.keys) if (after.pages.none { it.id == id }) verbs += CanvasVerb.PageRemove(id)
    return landInOrder(before, verbs) { key -> new[key]?.second?.kind }
}

/**
 * [verbs] in an order in which every move lands exactly where it was sent, worked out on [before] as the service will apply
 * them. A move whose cells something still holds waits for the others; when nothing can go on, one waiting move's item is
 * taken off and put back ([kindOf] its kind) once the rest have landed. Left as it was, a straight swap of two things
 * between full pages went to the service with each waiting on the other, the service put one "as near as there was room",
 * on another page, and that came back to home as his swap undone (Boss, 2026-10-21). What lands as sent is left as it is.
 */
internal fun landInOrder(before: Notebook, verbs: List<CanvasVerb>, kindOf: (String) -> ItemKind?): List<CanvasVerb> {
    var now = before
    val out = ArrayList<CanvasVerb>()
    val waiting = ArrayList<CanvasVerb.Move>()
    val lifted = ArrayList<CanvasVerb.Move>()
    fun land(v: CanvasVerb): Boolean {
        val r = applyVerb(now, v, bare = true)
        if (r.applied) { out += v; now = r.notebook }
        return r.applied
    }
    fun settle() {
        while (true) { val next = waiting.firstOrNull { applyVerb(now, it, bare = true).applied } ?: return; waiting -= next; land(next) }
    }
    for (v in verbs) {
        if (v is CanvasVerb.Move) { if (!land(v)) waiting += v else settle() } else if (!land(v)) out += v else settle()
    }
    while (waiting.isNotEmpty()) {
        settle()
        val stuck = waiting.firstOrNull { kindOf(it.key) != null } ?: break
        waiting -= stuck
        land(CanvasVerb.Remove(stuck.key))
        lifted += stuck
        settle()
    }
    for (m in lifted) if (!land(CanvasVerb.Place(m.key, kindOf(m.key)!!, m.page, m.cell))) out += CanvasVerb.Place(m.key, kindOf(m.key)!!, m.page, m.cell)
    // Anything still not landing goes as it was, for the service to settle as before.
    return out + waiting
}

/** Sent as `since` by a screen whose copy the service had lost: what it sends is that screen, whole. */
const val SYNC_WHOLE = -1L

/**
 * [sent] taken as a screen whole (round-2 review: re-seeding a service that had gone back to an older copy brought back
 * what he had removed since): the copy becomes exactly what the sent verbs make on an empty screen. Whatever is on the
 * copy and not there, or not there in the same place, is taken off first; then pages are made, renamed or taken away;
 * then what is still missing is put where the screen has it. Kept as verbs after the copy's own, so its history stays.
 */
fun takeWhole(log: VerbLog, sent: List<SentVerb>, columns: Int, rows: Int, origin: VerbOrigin): Pair<VerbLog, List<Took>> {
    val want = sent.fold(Notebook(rows = rows, pages = emptyList(), columns = columns)) { n, s -> if (s.schema > VERB_SCHEMA) n else applyVerb(n, s.verb, bare = true).notebook }
    var page = replayLog(log, columns, rows)
    fun where(n: Notebook) = n.pages.flatMap { p -> p.items.map { it.key to (p.id to it) } }.toMap()
    val wanted = where(want)
    val had = where(page)
    fun same(a: Pair<String, NotebookItem>, b: Pair<String, NotebookItem>) = a.first == b.first && a.second.cell == b.second.cell && a.second.kind == b.second.kind
    val verbs = ArrayList<CanvasVerb>()
    for ((key, at) in had) if (wanted[key]?.let { same(it, at) } != true) verbs += CanvasVerb.Remove(key)
    for (p in page.pages) if (want.pages.none { it.id == p.id }) verbs += CanvasVerb.PageRemove(p.id)
    want.pages.forEachIndexed { i, p ->
        val was = page.pages.firstOrNull { it.id == p.id }
        if (was == null) verbs += CanvasVerb.PageMake(p.id, p.name, want.pages.getOrNull(i - 1)?.id ?: dev.omalauncher.widgets.layout.PAGE_START)
        else if (was.name != p.name) verbs += CanvasVerb.PageRename(p.id, p.name)
    }
    for ((key, at) in wanted) if (had[key]?.let { same(it, at) } != true) at.second.cell?.let { verbs += CanvasVerb.Place(key, at.second.kind, at.first, it) }
    // A page's panel layout as the screen has it (a page made here has none yet).
    for (p in want.pages) if (p.panels != page.pages.firstOrNull { it.id == p.id }?.panels) verbs += CanvasVerb.PagePanels(p.id, p.panels)
    var next = log
    val request = sent.firstOrNull()?.request ?: "whole"
    for (v in verbs) {
        val done = applyVerb(page, v, bare = true)
        if (!done.applied) continue
        next = next.append(request, origin, v)
        page = done.notebook
    }
    return next to if (verbs.isEmpty()) emptyList() else listOf(Took(null, null, "the service had an older copy of this screen; it was made this screen again"))
}

/** Whether [item] on the Fold is one of the launcher's own thread tiles, whose keeper's echoes it ignores. */
fun isLauncherTile(item: NotebookItem) = item.kind == dev.omalauncher.widgets.layout.ItemKind.Thread

/** Sync's words on the wire: what a screen sends and what the service answers. */
object SyncWire {
    fun sent(s: SentVerb): org.json.JSONObject = org.json.JSONObject().put("base", s.base).put("request", s.request).put("schema", s.schema)
        .put("origin", org.json.JSONObject().put("device", s.origin.device).put("actor", s.origin.actor)).put("verb", dev.omalauncher.widgets.layout.VerbJson.verb(s.verb))

    fun sentOf(o: org.json.JSONObject): SentVerb? = runCatching {
        val schema = o.optInt("schema", VERB_SCHEMA)
        val origin = o.getJSONObject("origin")
        SentVerb(o.getLong("base"), o.getString("request"), VerbOrigin(origin.getString("device"), origin.getString("actor")), dev.omalauncher.widgets.layout.VerbJson.verbOf(o.getJSONObject("verb"), schema), schema)
    }.getOrNull()

    /** The log's entries after [since], as the service answers them (the log's own JSON for each). */
    fun tail(log: VerbLog, since: Long): org.json.JSONObject =
        org.json.JSONObject(dev.omalauncher.widgets.layout.VerbJson.log(log.copy(entries = log.entries.filter { it.version > since }))).put("version", log.version)
            // What the service has at [since], so a screen can tell its copy is still the one it synced with.
            .put("at", log.entries.firstOrNull { it.version == since }?.let(::mark) ?: org.json.JSONObject.NULL)

    /** An entry as the fork check compares it: its request and its verb. */
    fun mark(e: LoggedVerb): org.json.JSONObject = org.json.JSONObject().put("request", e.request).put("verb", dev.omalauncher.widgets.layout.VerbJson.verb(e.verb))
}

/**
 * One screen's side of sync, the same on every screen: what the service has given it so far ([synced], its copy of the
 * screen's canonical log) and what changed here since ([pending]). A round sends [pending] and takes back the log's tail,
 * which then is the screen's layout: the screen brings itself to [syncedPage] (the Fold through LiveNotebook, its only
 * writer). Its own changes come back in their canonical form, re-resolved if they had to be, so nothing is applied twice.
 */
class ScreenSync(val screen: String, val columns: Int, val rows: Int, var synced: VerbLog = VerbLog(screen)) {
    val syncedPage: Notebook get() = replayLog(synced, columns, rows)

    /** Whether the next round sends this screen whole: the service had lost its copy (or had an older one). */
    var whole = false
        private set

    /**
     * One round: [pending] sent through [exchange] (since, what was sent) → the service's tail, or null when it can't be
     * reached (nothing changes; the screen keeps working on its own copy and tries again). The notes the service gave for
     * what it re-resolved or dropped come back with the new layout. A refusal brings no layout (round-2 review: it brought
     * the old copy back, which undid his changes): the screen keeps its own, keeps what it would send, and says why.
     */
    fun round(pending: List<SentVerb>, exchange: (Long, List<SentVerb>) -> org.json.JSONObject?): Pair<Notebook?, List<String>>? {
        val answer = exchange(if (whole) SYNC_WHOLE else synced.version, pending) ?: return null
        // The service has no such version of this screen (its copy was lost or wiped): start again from nothing, so the next
        // round sends this screen whole and the service's copy is made again from it.
        if (answer.optInt("refused") == 409 && answer.optString("error").startsWith("the service has no version")) {
            synced = VerbLog(screen)
            whole = true
            return null
        }
        if (answer.has("refused")) return null to listOf("the canvas service refused this screen's sync: " + answer.optString("error"))
        val tail = dev.omalauncher.widgets.layout.VerbJson.logOf(answer.toString()) ?: return null
        // Only the tail after what this screen has, in order: an answer that skips versions is not taken.
        if (tail.entries.firstOrNull()?.let { it.version != synced.version + 1 } == true) return null
        // The service's entry at this screen's version isn't the one this screen has: its copy went back to an older one and
        // went on from there (round-3 review: the copies forked for good). This screen goes up whole next round.
        val at = answer.optJSONObject("at")
        val mine = synced.entries.lastOrNull()
        val same = at == null || (at.optString("request") == mine?.request &&
            runCatching { dev.omalauncher.widgets.layout.VerbJson.verbOf(at.getJSONObject("verb"), mine.schema) == mine.verb }.getOrDefault(false))
        if (!whole && mine != null && !same) {
            synced = VerbLog(screen)
            whole = true
            return null to listOf("the canvas service had gone back to an older copy of this screen; this screen goes up whole")
        }
        synced = synced.copy(entries = synced.entries + tail.entries)
        whole = false
        val notes = answer.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return syncedPage to notes
    }
}
