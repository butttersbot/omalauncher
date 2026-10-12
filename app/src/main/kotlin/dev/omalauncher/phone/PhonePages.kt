package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * The order every bridge request goes through, in one place: what it is, then the checks, then who handles it.
 * `status` and `stop` are answered whatever the state (stopping must always work). Everything else first needs
 * access (connected, not paused, unlocked, awake), then no approval waiting for the owner unless it only looks,
 * and only then is it handed on. Nothing is dispatched before [admit] returns.
 */
object PhoneRequests {
    /** Who handles an admitted request. */
    enum class Route { Status, Stop, Pages, Screenshot, Control }

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
    return PageMatch.None
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
