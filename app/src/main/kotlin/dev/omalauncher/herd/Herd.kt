package dev.omalauncher.herd

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.httpGet
import org.json.JSONObject
import java.io.IOException

// The herd: every coding agent on every computer of Boss's tailnet, as the reporters of ~/ClaudeSync/projects/herd
// tell it. Each computer runs one on port 8479, and each answers for all of them at /v1/herd, so the phone (which
// can't list the tailnet itself) only needs one that is up. The sheep on home shows it (ui/HerdPet.kt).

/** The reporters to ask, in order: the Mac Pro is always on; the MacBook and the MG1 come and go. */
val HERD_HOSTS = listOf("jacks-mac-pro", "macbook-pro", "omarchy-mg1")
const val HERD_PORT = 8479

fun herdUrl(
    host: String,
    path: String,
) = "http://$host:$HERD_PORT$path"

/**
 * The first host whose [ask] answers, starting with [preferred] (the one that answered last) so a host that is down
 * costs a timeout only once. A host that throws or answers null is passed over; null when none answers.
 */
fun <T : Any> firstAnswer(
    hosts: List<String>,
    preferred: String?,
    ask: (String) -> T?,
): Pair<String, T>? {
    val order = listOfNotNull(preferred?.takeIf { it in hosts }) + hosts.filter { it != preferred }
    for (host in order) {
        val answer =
            try {
                ask(host)
            } catch (_: IOException) {
                null
            }
        if (answer != null) return host to answer
    }
    return null
}

/** What the sheep's page asks of the phone. */
sealed interface PetCommand {
    data object Expand : PetCommand

    data object Collapse : PetCommand

    data class Show(val id: String) : PetCommand

    data class Copy(val text: String) : PetCommand

    /** Walk [dx] dp sideways at [speed] dp a second. */
    data class Stroll(val dx: Float, val speed: Float) : PetCommand

    /** Room of [w]×[h] dp around the sheep for its speech bubble, or back to just the sheep when null. */
    data class Room(val w: Float?, val h: Float?) : PetCommand

    data object Unknown : PetCommand
}

fun petCommand(json: String): PetCommand {
    val o =
        try {
            JSONObject(json)
        } catch (_: Exception) {
            return PetCommand.Unknown
        }
    return when (o.optString("cmd")) {
        "expand" -> PetCommand.Expand
        "collapse" -> PetCommand.Collapse
        "show" -> safeSessionId(o.optString("id"))?.let { PetCommand.Show(it) } ?: PetCommand.Unknown
        "copy" -> PetCommand.Copy(o.optString("text"))
        // Held to sizes a phone screen has room for, whatever the page asks.
        "stroll" ->
            o.optDouble("dx").takeIf { it.isFinite() }?.let {
                val speed = o.optDouble("speed", 40.0).takeIf { s -> s.isFinite() } ?: 40.0
                PetCommand.Stroll(it.toFloat().coerceIn(-2000f, 2000f), speed.toFloat().coerceIn(10f, 400f))
            } ?: PetCommand.Unknown
        "room" -> {
            val w = o.optDouble("w").takeIf { it.isFinite() }
            val h = o.optDouble("h").takeIf { it.isFinite() }
            if (w != null && h != null) PetCommand.Room(w.toFloat().coerceIn(0f, 1000f), h.toFloat().coerceIn(0f, 1000f))
            else PetCommand.Room(null, null)
        }
        else -> PetCommand.Unknown
    }
}

/** Where the floating sheep's window goes, in pixels, and which side of it the sheep stands on. */
data class SheepWindow(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val side: String,
)

/**
 * The window around the sheep whose own [sheep]-sized square is at [sx], [sy], grown to [w]×[h] for its speech
 * bubble without moving it (the Mac app's layout()): the sheep keeps to the window's bottom corner, the right one
 * unless the window would then run off the screen's left edge, when it grows rightwards from the bottom-left.
 */
fun sheepWindow(
    sx: Int,
    sy: Int,
    sheep: Int,
    w: Int,
    h: Int,
    screenW: Int,
    screenH: Int,
): SheepWindow {
    val ww = maxOf(w, sheep).coerceAtMost(maxOf(screenW, sheep))
    val wh = maxOf(h, sheep).coerceAtMost(maxOf(screenH, sheep))
    val side = if (sx + sheep - ww >= 0) "right" else "left"
    val x = if (side == "right") sx + sheep - ww else sx
    return SheepWindow(x.coerceIn(0, maxOf(0, screenW - ww)), (sy + sheep - wh).coerceAtLeast(0), ww, wh, side)
}

/** Session ids are uuids or oma-runner's t-ids; anything else never goes into a URL. */
fun safeSessionId(id: String): String? = id.takeIf { Regex("[0-9A-Za-z-]{4,64}").matches(it) }

/**
 * A reporter's answer, checked to be one JSON object before it reaches the page's script (it is handed over as
 * code), or null when it isn't.
 */
fun herdJson(text: String?): String? =
    try {
        text?.let { JSONObject(it).toString() }
    } catch (_: Exception) {
        null
    }

/** What the page is given when no reporter answers: no machines, and why. */
fun herdUnreachable(): String =
    JSONObject()
        .put("machines", org.json.JSONArray())
        .put("others", org.json.JSONArray())
        .put("error", "Can't reach the herd. Is Tailscale on? It doesn't come back by itself after a restart.")
        .toString()

/** The thin network side: blocking, so call it off the main thread. */
object Herd {
    @Volatile private var answered: String? = null

    /** Every machine and its agents, from whichever reporter answers first; null when none does. */
    fun status(): String? = ask("/v1/herd")

    /** One session and its last few exchanges, found on whichever machine has it. */
    fun session(id: String): String? = safeSessionId(id)?.let { ask("/v1/herd/sessions/$it") }

    private fun ask(path: String): String? =
        firstAnswer(HERD_HOSTS, answered) { host -> herdJson(String(httpGet(herdUrl(host, path), 3_000, 20_000))) }
            ?.let { (host, json) ->
                answered = host
                json
            }
}

/**
 * Whether the sheep is shown, on home and over other apps (Boss, 2026-10-15: "a button to toggle it on and off",
 * "always on top … even when I'm in an app"): turned off and on in settings and by its quick-settings tile. While
 * it floats over everything ([floating]), home doesn't draw its own. Where it was left is kept here too.
 */
object HerdSheep {
    private const val PREFS = "herd"
    private const val KEY = "shown"

    var shown by mutableStateOf(true)
        private set

    /** The sheep is up over everything, home included, in the accessibility service's window. */
    var floating by mutableStateOf(false)

    /** Told when [shown] changes, so the window over other apps follows at once. */
    @Volatile var changed: (() -> Unit)? = null

    /**
     * Where home's sheep and its open panel are, in window pixels, or null. Home's pages leave a touch that starts
     * here to the sheep: they watch every finger first, and would take a swipe up in its lists for the app list.
     */
    var onHome: androidx.compose.ui.geometry.Rect? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restore(context: Context) {
        shown = prefs(context).getBoolean(KEY, true)
    }

    fun set(
        context: Context,
        on: Boolean,
    ) {
        shown = on
        prefs(context).edit().putBoolean(KEY, on).apply()
        changed?.invoke()
    }

    /** Where he left the sheep over other apps: its top-left in screen pixels; null until he has moved it. */
    fun spot(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        return if (p.contains("x")) p.getInt("x", 0) to p.getInt("y", 0) else null
    }

    fun keep(
        context: Context,
        x: Int,
        y: Int,
    ) = prefs(context).edit().putInt("x", x).putInt("y", y).apply()
}
