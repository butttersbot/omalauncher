package dev.omalauncher.widgets

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import dev.omalauncher.assist.AgentThread
import dev.omalauncher.widgets.annotations.Box
import dev.omalauncher.widgets.layout.firstFit

/** Where the thread tiles' stamps are kept. Saved: never rename. */
const val AGENT_TILES_PREFS = "agent-tiles"
const val AGENT_TILES_STAMPS = "stamps"

/**
 * The stamps as home's last look left them, so the chat shows the threads home has tiles for ([liveThreads])
 * without a look of its own. Read only: [AgentTileKeeper] alone writes them.
 */
fun savedTileStamps(context: Context): Map<String, TileStamp> =
    tileStampsFrom(context.getSharedPreferences(AGENT_TILES_PREFS, Context.MODE_PRIVATE).getString(AGENT_TILES_STAMPS, null))

/**
 * Keeps the thread tiles in the [notebook] as AgentTiles.kt decides: this only carries out the three changes
 * [agentWrites] can ask for, and remembers the stamps between looks. [saved] is what [save] was last given.
 * It does nothing until the notebook is read and its changes are kept, so no tile is placed in a session that
 * is not written, to be missed at the next start. Main thread, like the notebook.
 */
class AgentTileKeeper(
    private val notebook: LiveNotebook,
    saved: String?,
    private val save: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var stamps = tileStampsFrom(saved)

    /** The tiles as home draws them, by notebook key. */
    val tiles = mutableStateMapOf<String, AgentTile>()

    /** The tiles the Agents page had no room for, so that is logged once for each and not at every look. */
    private val unplaced = HashSet<String>()

    /**
     * Looks at [threads] at [now] and makes the notebook's thread tiles match. [listed] is whether the Mac's
     * own list has been heard this run. [pinned] and [archived] are the threads he pinned and archived in the
     * chat, by key. [scraps] gives the notes and stickers lying on a page, null while they are still being read:
     * a tile waits for them, since it is placed clear of those too.
     */
    fun keep(
        threads: List<AgentThread>,
        listed: Boolean,
        now: Long,
        pinned: Set<String> = emptySet(),
        archived: Set<String> = emptySet(),
        scraps: (String) -> List<Box>?,
    ) {
        if (!notebook.opened || !notebook.kept) return
        val placed = notebook.threadTiles()
        val agents = agentsPage(notebook.pages)
        val plan = agentTilePlan(threads, placed, agents, stamps, now, listed, pinned, archived, room(agents, placed, scraps) ?: Int.MAX_VALUE)
        // Said once for each, as a tile with no cells left for it always was.
        for (tile in plan.left) if (tile !in placed && unplaced.add(tile)) log("no room on the $AGENTS_PAGE page for $tile: it is not shown")
        var writes = agentWrites(notebook.pages, placed, plan)
        if (AgentWrite.MakePage in writes) {
            // The way a page is added and named everywhere else: after the last, then called by its name.
            val made = notebook.addPage() ?: return
            notebook.renamePage(made, AGENTS_PAGE)
            log("added the $AGENTS_PAGE page")
            writes = agentWrites(notebook.pages, placed, plan)
        }
        for (write in writes) {
            when (write) {
                AgentWrite.MakePage -> {}
                // Asked again of the notebook as it is now: only a thread tile, and only where the plan saw it.
                is AgentWrite.Remove -> if (isThreadTile(write.tile) && notebook.pageOf(write.tile) == write.page) notebook.forget(write.tile)
                is AgentWrite.Place -> place(write, scraps(write.page) ?: continue)
            }
        }
        // In rank order: the threads at work first, then the most recent (Boss, 2026-10-24).
        agentsPage(notebook.pages)?.let { page ->
            val at = notebook.threadTiles().filterValues { it == page }.keys.mapNotNull { key -> notebook.threadTileCell(key)?.let { key to it } }.toMap()
            val moves = agentTileCells(plan.order, at)
            if (moves.isNotEmpty()) notebook.setThreadTileCells(page, moves)
        }
        // Remembered as the notebook now is, the tiles just placed included: one he takes off before the next look is still known to have been there.
        val there = notebook.threadTiles().keys
        val next = plan.stamps.mapValues { (thread, stamp) -> stamp.copy(placed = threadTileKey(thread) in there) }
        if (next != stamps) {
            stamps = next
            save(tileStampsText(next))
        }
        tiles.keys.retainAll(plan.show.keys)
        for ((key, tile) in plan.show) if (tiles[key] != tile) tiles[key] = tile
    }

    /**
     * How many thread tiles the Agents page holds: the ones on it now, and as many more of [AGENT_TILE_SIZE] as fit
     * in cells nothing else has. Every tile with no page yet; null while its notes and stickers are still being read,
     * and the tiles then wait, unplaced, as they always have.
     */
    private fun room(
        agents: String?,
        placed: Map<String, String>,
        scraps: (String) -> List<Box>?,
    ): Int? {
        agents ?: return Int.MAX_VALUE
        val lying = scraps(agents) ?: return null
        val board = notebook.board(agents)
        val mine = placed.filterValues { it == agents }.keys
        val taken = board.tiles.filter { it.name !in mine }.map { it.cell } + lying.map(board::cells)
        val filled = taken.toMutableList()
        var room = 0
        while (true) {
            val cell = firstFit(AGENT_TILE_SIZE.first, AGENT_TILE_SIZE.second, filled, board.columns, board.rows) ?: break
            filled += cell
            room++
        }
        return room
    }

    /**
     * Puts a new tile in the first cells of its page that nothing has: no tile, no place kept for a missing
     * widget, and no part of a note or sticker ([lying]). On a full page it is not put anywhere.
     */
    private fun place(
        write: AgentWrite.Place,
        lying: List<Box>,
    ) {
        val board = notebook.board(write.page)
        val taken = board.tiles.map { it.cell } + lying.map(board::cells)
        val cell = firstFit(AGENT_TILE_SIZE.first, AGENT_TILE_SIZE.second, taken, board.columns, board.rows)
        if (cell == null) {
            if (unplaced.add(write.tile)) log("no room on the $AGENTS_PAGE page for ${write.tile}: it is not shown")
            return
        }
        unplaced -= write.tile
        notebook.placeThreadTile(write.tile, write.page, cell)
    }
}
