package dev.omalauncher.widgets

import dev.omalauncher.assist.AgentThread
import dev.omalauncher.assist.PHONE_THREAD
import dev.omalauncher.assist.ThreadState
import dev.omalauncher.oma.chatId
import org.json.JSONObject

/*
 * The thread tiles on home (Boss, 2026-10-13: "dedicated page", "12 hours is fine";
 * design/oma-threads-mockup.html). The launcher keeps one page named Agents and puts a tile there for each
 * thread that is at work or was lately: the phone's builder and the project threads on the Mac. This is the
 * one place where the launcher writes to his pages by itself, so what it may write is decided here, by plain
 * functions that are unit tested, and it is this much and no more: a page named Agents is added when a tile
 * needs one and there is none; a tile is placed on that page, in cells nothing has; a tile is taken off that
 * page when its thread has been idle for [AGENT_TILE_IDLE_HOURS]. Nothing on any other page is placed, moved
 * or removed, and a tile he has carried to another page is his from then on: it stays, and only keeps
 * showing how its thread stands. A tile he removes, or whose page he deletes, stays off until its thread next
 * starts work. AgentTileKeeper does what these say and nothing else.
 */

/** What the page the launcher keeps for thread tiles is called. A page he renames is no longer it. */
const val AGENTS_PAGE = "Agents"

/** How long a thread's tile stays once the thread is idle. */
const val AGENT_TILE_IDLE_HOURS = 12

/** How long before it leaves a tile is drawn faded, so its going is not a surprise. */
const val AGENT_TILE_FADE_HOURS = 1

/** A new thread tile's cells across and down: two side by side on the six columns, with room for its five lines. */
val AGENT_TILE_SIZE = 3 to 3

/** The smallest a thread tile can be made: its name and its state still show. */
val AGENT_TILE_MIN = 2 to 2

/**
 * Ahead of a thread's key in the key its tile has in the notebook. A widget's key is its file's name, an
 * app's begins "app:" or "linux:" and a folder's "folder:", so this is no other item's. Saved: never rename.
 */
const val THREAD_TILE_PREFIX = "thread:"

/** The kind a thread's tile is told as by `oma-phone board`, beside "widget", "app" and "folder". */
const val AGENT_TILE_KIND = "agent"

/** The notebook key of the tile of the thread [thread] ([AgentThread.key]). */
fun threadTileKey(thread: String) = THREAD_TILE_PREFIX + thread

/** Whether the notebook item [key] is a thread's tile. */
fun isThreadTile(key: String) = key.startsWith(THREAD_TILE_PREFIX)

/** The key of the thread whose tile [key] is; null for any other item. */
fun tileThread(key: String): String? = key.takeIf(::isThreadTile)?.removePrefix(THREAD_TILE_PREFIX)?.takeIf { it.isNotEmpty() }

/** The id of the page thread tiles are kept on: the first one called [AGENTS_PAGE], whatever its capitals; null when there is none. */
fun agentsPage(pages: List<PageLabel>): String? = pages.firstOrNull { it.name.equals(AGENTS_PAGE, ignoreCase = true) }?.id

/** Whether a tile shows, and how. */
enum class TileLife { Stays, Fading, Gone }

private const val HOUR_MS = 3_600_000L

/** How old a busy thread's stamp gets before it is written again: far less than the hour a tile fades over. */
const val STAMP_EVERY_MS = 60_000L

/**
 * What becomes of the tile of a thread in [state] that was last busy at [busy] (null if it never was, as far
 * as the phone knows). It never leaves while the thread works, has requests queued or waits for his OK. Idle, or
 * ended in a failure that nothing is waiting on, it stays [AGENT_TILE_IDLE_HOURS], faded for the last
 * [AGENT_TILE_FADE_HOURS], then goes: a red tile that stayed until he sent that thread something again would pile up.
 */
fun tileLife(state: ThreadState, busy: Long?, now: Long): TileLife {
    if (state.active && state != ThreadState.Attention) return TileLife.Stays
    if (state == ThreadState.Attention && busy == null) return TileLife.Stays
    val idle = now - (busy ?: return TileLife.Gone)
    return when {
        idle >= AGENT_TILE_IDLE_HOURS * HOUR_MS -> TileLife.Gone
        idle >= (AGENT_TILE_IDLE_HOURS - AGENT_TILE_FADE_HOURS) * HOUR_MS -> TileLife.Fading
        else -> TileLife.Stays
    }
}

/**
 * Whether a thread is shown at all, by its dot and tab in the chat and its tile on home alike: [archived] never,
 * [pinned] always, any other by [tileLife]. [busy] is when it was last busy ([lastBusy]).
 */
fun threadLife(state: ThreadState, busy: Long?, now: Long, pinned: Boolean, archived: Boolean): TileLife = when {
    archived -> TileLife.Gone
    pinned -> TileLife.Stays
    else -> tileLife(state, busy, now)
}

/** When [thread] was last busy as far as the phone knows: when it last worked, or when it was last seen busy ([stamp]). */
fun lastBusy(thread: AgentThread, stamp: TileStamp?): Long? = listOfNotNull(thread.worked, stamp?.busy).maxOrNull()

/**
 * Whether a thread remembered by its [stamp] but not heard of now is gone: the Mac's own list has been heard
 * this run ([listed]) without it, or it has rested [AGENT_TILE_IDLE_HOURS] since it was last seen busy. Until
 * then it is only not heard of yet, as after a restart before the Mac has answered.
 */
fun forgotten(stamp: TileStamp, now: Long, listed: Boolean): Boolean = listed || tileLife(ThreadState.Idle, stamp.busy, now) == TileLife.Gone

/** What the chat's dot of a thread that is remembered but not heard of now says. */
const val NOT_HEARD_FROM = "not heard from"

/**
 * The threads Boss is shown at [now]: one set for the chat's dots and tabs and for home's tiles (Boss,
 * 2026-10-13: tiles "show up while they're active, and then they fall off after … a certain amount of time of
 * inactivity", "12 hours is fine"). Of [threads], each [threadLife] keeps; then each thread of [stamps] that had
 * a tile at the last look and is not heard of now, until it is [forgotten], by its name alone, at rest and out
 * of reach. Those stand after the builders and the Mac's threads and before the new chats, so the newest chat
 * stays beside ＋. Two tiles stay that this set leaves out: one he carried to another page, which is his, and
 * none he took off home shows here either way, since the chat has no tile to take off.
 */
fun liveThreads(
    threads: List<AgentThread>,
    stamps: Map<String, TileStamp>,
    now: Long,
    listed: Boolean,
    pinned: Set<String> = emptySet(),
    archived: Set<String> = emptySet(),
): List<AgentThread> {
    val known = threads.map { it.key }.toSet()
    val live = threads.filter { threadLife(it.state, lastBusy(it, stamps[it.key]), now, it.key in pinned, it.key in archived) != TileLife.Gone }
    val remembered = stamps.filter { (key, stamp) -> key !in known && stamp.placed && key !in archived && !forgotten(stamp, now, listed) }
        .map { (key, stamp) -> AgentThread(key, stamp.name, ThreadState.Idle, "", NOT_HEARD_FROM, stamp.busy, reachable = false) }
    val (chats, others) = live.partition { chatId(it.key) != null }
    return others + remembered + chats
}

/** Why a thread has no tile though it could: he took its tile off home, and it stays off until the thread next starts work. */
enum class TileHidden(val id: String) {
    No(""),

    /** Taken off while the thread was busy: that spell of work has to end first. Saved: never rename. */
    UntilIdle("until-idle"),

    /** The thread is resting: the tile comes back when it next starts work. Saved: never rename. */
    UntilWork("until-work"),
    ;

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: No }
}

/**
 * What the launcher remembers of a thread between looks, by the thread's key: its [name], so a tile can be
 * drawn before the Mac has answered after a restart; when it was last seen [busy], in milliseconds, null if
 * never; whether its tile was in the notebook at the last look ([placed]); and whether he took it off ([hidden]).
 */
data class TileStamp(val name: String, val busy: Long? = null, val placed: Boolean = false, val hidden: TileHidden = TileHidden.No)

/** A thread tile as home draws it. [thread] is null while nothing is heard of the thread: the tile then shows [name] alone. */
data class AgentTile(val name: String, val thread: AgentThread?, val fading: Boolean = false)

/**
 * What is to be done about the tiles. [show] is every tile there will be, by notebook key; [add] the tiles to
 * place and [remove] the tiles to take off, both by notebook key; [stamps] what to remember from here on.
 */
data class TilePlan(val show: Map<String, AgentTile>, val add: List<String>, val remove: List<String>, val stamps: Map<String, TileStamp>)

/**
 * Whether a thread's tile is kept off home from here on. [old] is what was remembered, [there] whether the
 * tile is in the notebook now, [active] whether the thread is busy. A tile that was there at the last look
 * and is not now was taken off by him (removed, or gone with a page he deleted), since a tile the launcher
 * takes off is remembered as not there: it stays off until the thread has rested and starts work again.
 */
fun hiddenNext(old: TileStamp?, there: Boolean, active: Boolean): TileHidden = when {
    there || old == null -> TileHidden.No
    old.placed -> if (active) TileHidden.UntilIdle else TileHidden.UntilWork
    old.hidden == TileHidden.UntilIdle && !active -> TileHidden.UntilWork
    old.hidden == TileHidden.UntilWork && active -> TileHidden.No
    else -> old.hidden
}

/**
 * Which tiles there should be at [now]. [threads] is every thread as it stands; [placed] the thread tiles in
 * the notebook, by key, with the id of the page each is on; [agents] the id of the Agents page, null when
 * there is none; [stamps] what was remembered; [listed] whether the Mac's own list of threads has been heard
 * since the launcher started.
 *
 * A thread gets a tile while [tileLife] lets it show and he has not taken it off ([hiddenNext]), and its tile
 * on the Agents page goes when [tileLife] says gone. A tile on any other page is his, put there by his own
 * hand: it is never removed here, whatever becomes of its thread. A tile whose thread is not among [threads]
 * stays until the Mac's list has been heard, since after a restart a thread is only not heard of yet; once
 * the list is heard and still lacks it, or [AGENT_TILE_IDLE_HOURS] have passed with nothing heard, the thread
 * is gone and its tile with it.
 *
 * In the chat he may pin a thread or archive it (Boss, 2026-10-13), by its key: a [pinned] thread's tile never
 * leaves by itself, and an [archived] one's leaves the Agents page as a gone one does, to come back as any tile
 * does once the thread is no longer archived.
 */
fun agentTilePlan(
    threads: List<AgentThread>,
    placed: Map<String, String>,
    agents: String?,
    stamps: Map<String, TileStamp>,
    now: Long,
    listed: Boolean,
    pinned: Set<String> = emptySet(),
    archived: Set<String> = emptySet(),
): TilePlan {
    val show = LinkedHashMap<String, AgentTile>()
    val add = ArrayList<String>()
    val remove = ArrayList<String>()
    val next = HashMap<String, TileStamp>()
    val known = threads.map { it.key }.toSet()
    fun his(tile: String) = placed[tile].let { it != null && it != agents }
    for (thread in threads) {
        val tile = threadTileKey(thread.key)
        val old = stamps[thread.key]
        val there = tile in placed
        val active = thread.state.active
        // A busy thread is stamped again only once the stamp has grown old, so a long task does not rewrite it at every look.
        val fresh = now.takeIf { active && (old?.busy == null || now - old.busy >= STAMP_EVERY_MS) }
        val busy = listOfNotNull(lastBusy(thread, old), fresh).maxOrNull()
        val hidden = hiddenNext(old, there, active)
        val life = threadLife(thread.state, busy, now, thread.key in pinned, thread.key in archived)
        when {
            there && his(tile) -> show[tile] = AgentTile(thread.name, thread)
            life == TileLife.Gone -> if (there) remove += tile
            there -> show[tile] = AgentTile(thread.name, thread, fading = life == TileLife.Fading)
            hidden == TileHidden.No -> {
                add += tile
                show[tile] = AgentTile(thread.name, thread, fading = life == TileLife.Fading)
            }
        }
        val stays = there && tile !in remove
        if (busy != null || stays || hidden != TileHidden.No) next[thread.key] = TileStamp(thread.name, busy, stays, hidden)
    }
    for ((key, old) in stamps) {
        if (key in known) continue
        val tile = threadTileKey(key)
        val there = tile in placed
        val gone = forgotten(old, now, listed)
        // One archived in the chat leaves the Agents page as well, as [liveThreads] leaves it out, and is still remembered.
        val off = gone || key in archived
        when {
            there && (his(tile) || !off) -> {
                show[tile] = AgentTile(old.name, null)
                next[key] = old.copy(placed = true, hidden = TileHidden.No)
            }
            there -> {
                remove += tile
                if (!gone) next[key] = old.copy(placed = false)
            }
            // Forgotten with its thread.
            gone -> {}
            else -> next[key] = old.copy(placed = false, hidden = hiddenNext(old, there = false, active = false))
        }
    }
    // A tile nothing is remembered of: his stays, nameless until its thread is heard of; the launcher's own goes.
    for (tile in placed.keys) {
        val key = tileThread(tile) ?: continue
        if (key in known || key in stamps) continue
        if (his(tile)) show[tile] = AgentTile("", null) else remove += tile
    }
    return TilePlan(show, add, remove, next)
}

/** One change the launcher makes to his pages for the thread tiles. There are these three and no others. */
sealed interface AgentWrite {
    /** A new, empty page after the last, called [AGENTS_PAGE]. Nothing else is done until it is there. */
    data object MakePage : AgentWrite

    /** Puts the thread tile [tile] on [page], in cells nothing has; with no such cells it is not put anywhere. */
    data class Place(val page: String, val tile: String) : AgentWrite

    /** Takes the thread tile [tile] off [page]. */
    data class Remove(val page: String, val tile: String) : AgentWrite
}

/**
 * The changes [plan] comes to on the notebook's [pages], where [placed] says which page each thread tile is
 * on. Every one of them is on the Agents page ([agentsPage]) and is a thread tile's: a tile is placed there
 * and taken off there, and nothing here names another page or another kind of item. With tiles to place and
 * no Agents page, the one change is the page itself; the tiles follow when this is asked again with the page
 * there. A tile to remove that is not on the Agents page is left where it is.
 */
fun agentWrites(pages: List<PageLabel>, placed: Map<String, String>, plan: TilePlan): List<AgentWrite> {
    val add = plan.add.filter(::isThreadTile)
    val agents = agentsPage(pages) ?: return if (add.isEmpty()) emptyList() else listOf(AgentWrite.MakePage)
    return plan.remove.filter { isThreadTile(it) && placed[it] == agents }.map { AgentWrite.Remove(agents, it) } +
        add.filter { it !in placed }.map { AgentWrite.Place(agents, it) }
}

/**
 * The small line over a tile's name: where the thread runs and, while it works, how long its turn has run,
 * counting up as a terminal agent's does ("THIS PHONE · 2:14"); at rest, how long ago it last worked
 * ("MAC · 2 MIN"). A busy thread whose turn the phone did not see begin says "NOW". A new chat is the Mac's
 * before it has a thread there too: at rest it says it waits for a project, never "THIS PHONE".
 */
fun tileWhere(tile: AgentTile, now: Long): String {
    val thread = tile.thread ?: return "MAC · NOT HEARD FROM"
    val place = if (thread.mac) "MAC" else "THIS PHONE"
    if (thread.mac && !thread.reachable) return "$place · OUT OF REACH"
    if (thread.unplaced && thread.state != ThreadState.Working && thread.state != ThreadState.Queued) return "$place · WAITING FOR A PROJECT"
    if (!thread.state.active) return "$place · " + agoWords(thread.worked, now)
    return "$place · " + (thread.since?.let { elapsedWords(it, now) } ?: "NOW")
}

/** How long since [since], as a clock counts it: "0:07", "12:40", "1:05:09". */
fun elapsedWords(since: Long, now: Long): String {
    val seconds = maxOf(0L, now - since) / 1000L
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** How long ago [then] was, in a tile's few capitals; "NOW" within the minute, and for a time not known. */
fun agoWords(then: Long?, now: Long): String {
    val minutes = (now - (then ?: now)) / 60_000L
    return when {
        minutes < 1 -> "NOW"
        minutes < 60 -> "$minutes MIN"
        minutes < 48 * 60 -> "${minutes / 60} HR"
        else -> "${minutes / (24 * 60)} DAYS"
    }
}

/** The stamps as they are kept across a restart. */
fun tileStampsText(stamps: Map<String, TileStamp>): String =
    JSONObject().apply {
        for ((thread, stamp) in stamps) put(thread, JSONObject().put("name", stamp.name).putOpt("busy", stamp.busy).put("placed", stamp.placed).put("hidden", stamp.hidden.id))
    }.toString()

/** The kept stamps; what can't be read is left out, and an unreadable whole is none. */
fun tileStampsFrom(text: String?): Map<String, TileStamp> {
    val saved = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return emptyMap()
    return saved.keys().asSequence().mapNotNull { thread ->
        val stamp = saved.optJSONObject(thread) ?: return@mapNotNull null
        thread to TileStamp(stamp.optString("name").take(60), stamp.optLong("busy", 0L).takeIf { it > 0 }, stamp.optBoolean("placed"), TileHidden.of(stamp.optString("hidden")))
    }.toMap()
}
