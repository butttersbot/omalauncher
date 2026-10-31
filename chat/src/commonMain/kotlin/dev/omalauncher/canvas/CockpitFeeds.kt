package dev.omalauncher.canvas

import org.json.JSONArray
import org.json.JSONObject

/*
 * What the cockpit reads beyond the threads (Soma Canvas slice 3b): herd's machines and sessions (`GET /v1/herd` from
 * whichever reporter answers), the pocket (Tailshare's `GET /v1/pocket` on the Mac Pro), and agent mail for a nudge
 * (`POST /v1/msg` on the session's machine). Parsing is plain functions here, unit tested; the HTTP is handed in, so the
 * phone and the desktop use their own and a test uses none. Everything answers on the tailnet only, checked by Tailscale.
 */

/** The reporters asked for herd, in order: the Mac Pro is always on (the phone's own list, herd/Herd.kt). */
val COCKPIT_HERD_HOSTS = listOf("jacks-mac-pro", "macbook-pro", "omarchy-mg1")
const val COCKPIT_HERD_PORT = 8479
const val COCKPIT_TAILSHARE_PORT = 8480
const val COCKPIT_POCKET_HOST = "jacks-mac-pro"

/** A machine as herd sees it: whether it is [online], its load, and its GPU when it has one (the MG1). */
data class HerdMachine(val name: String, val online: Boolean, val load: Double? = null, val gpu: String? = null, val address: String = "",
    /** From the machine's own report: each absent when it can't say (a phone has no CPU figure until it runs the newer oma-herd). */
    val cpuPercent: Double? = null, val ramUsedMb: Long? = null, val ramTotalMb: Long? = null)

/** A session herd lists: a Claude Code session (CLI or desktop app), a runner thread, a Codex or Grok session. */
data class HerdSession(
    val id: String,
    val machine: String,
    val kind: String,
    val app: String,
    val title: String,
    val project: String,
    /** herd's state: "working", "needs-you", "your-turn", "idle" or "closed". */
    val state: String,
    val live: Boolean,
    val updated: Long,
)

data class HerdSnapshot(val machines: List<HerdMachine>, val sessions: List<HerdSession>)

/** A session id herd and agent mail accept: what `safeSessionId` on the phone allows, plus a runner thread's `machine/id`. */
private val SESSION_ID = Regex("[0-9A-Za-z-]{4,64}(/[0-9A-Za-z-]{4,64})?")

/** The most of anything read from herd or the pocket that is kept: a module shows a handful, never thousands. */
const val MAX_SESSIONS = 200
const val MAX_POCKET = 50

/** herd's `/v1/herd` answer; what can't be read is left out, and an unreadable whole is null. */
fun herdSnapshot(text: String?): HerdSnapshot? = runCatching {
    val o = JSONObject(text.orEmpty())
    val machines = o.optJSONArray("machines") ?: return null
    val ms = ArrayList<HerdMachine>()
    val ss = ArrayList<HerdSession>()
    for (i in 0 until machines.length()) {
        val m = machines.optJSONObject(i) ?: continue
        val name = m.optString("name").takeIf { it.isNotBlank() } ?: continue
        val report = m.optJSONObject("report")
        val info = report?.optJSONObject("machine")
        val gpu = info?.optJSONObject("gpu")?.let { g -> "GPU ${g.optInt("used_mb") / 1024}/${g.optInt("total_mb") / 1024} GB" }
        ms += HerdMachine(name.take(64), m.optBoolean("online"), info?.takeIf { it.has("load") }?.optDouble("load"), gpu, m.optString("address").take(64),
            cpuPercent = info?.takeIf { it.has("cpu_percent") && !it.isNull("cpu_percent") }?.optDouble("cpu_percent")?.takeIf { it in 0.0..100.0 },
            ramUsedMb = info?.takeIf { it.has("ram_used_mb") && !it.isNull("ram_used_mb") }?.optLong("ram_used_mb"),
            ramTotalMb = info?.takeIf { it.has("ram_total_mb") && !it.isNull("ram_total_mb") }?.optLong("ram_total_mb")?.takeIf { it > 0 })
        val sessions = report?.optJSONArray("sessions") ?: JSONArray()
        for (j in 0 until sessions.length()) {
            val s = sessions.optJSONObject(j) ?: continue
            val id = s.optString("id").takeIf { SESSION_ID.matches(it) } ?: continue
            if (ss.size >= MAX_SESSIONS) break
            ss += HerdSession(id, name.take(64), s.optString("kind").take(16), s.optString("app").take(16), s.optString("title").take(120), s.optString("project").take(64), s.optString("state").take(16), s.optBoolean("live"), (s.optDouble("updated", 0.0) * 1000).toLong())
        }
    }
    HerdSnapshot(ms, ss)
}.getOrNull()

/** Claude Code sessions not run by a runner thread: the ones the cockpit shows read-only, with a nudge. */
fun desktopSessions(snapshot: HerdSnapshot?, project: String? = null): List<HerdSession> =
    snapshot?.sessions.orEmpty().filter { it.kind == "claude" && it.app != "oma" && it.state != "closed" && (project == null || it.project == project) }
        .sortedByDescending { it.updated }.take(MAX_POCKET)

/** A pocket item: one file in the shared folder on the Mac Pro. */
data class PocketItem(val id: String, val name: String, val size: Long, val time: Long, val device: String, val pinned: Boolean, val kind: String)

/** Tailshare's `/v1/pocket` answer, newest first; what can't be read is left out. */
fun pocketItems(text: String?): List<PocketItem> = runCatching {
    val items = JSONObject(text.orEmpty()).optJSONArray("items") ?: return emptyList()
    (0 until items.length()).mapNotNull { i ->
        val o = items.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { Regex("[0-9A-Za-z-]{4,64}").matches(it) } ?: return@mapNotNull null
        PocketItem(id, o.optString("name").take(200), o.optLong("size"), (o.optDouble("t", 0.0) * 1000).toLong(), o.optString("device").take(64), o.optBoolean("pinned"), o.optString("kind").take(16))
    }.sortedByDescending { it.time }.take(MAX_POCKET)
}.getOrElse { emptyList() }

/** What a nudge says, unless he says otherwise: an idle session picks it up through its Stop hook (`tmsg hook-wait`). */
const val NUDGE_TEXT = "Boss nudged you from Soma: please check in. What are you on, and does anything need him?"

/** The agent mail a nudge is: to [session] on its machine, from Soma, the way `tmsg send` writes it. */
fun nudgeMessage(session: String, text: String = NUDGE_TEXT): String? {
    if (!SESSION_ID.matches(session)) return null
    return JSONObject()
        .put("to", JSONObject().put("session", session))
        .put("from", JSONObject().put("agent", "soma").put("session", "soma").put("title", "Soma"))
        .put("text", text.take(2000))
        .put("reply_to", JSONObject.NULL)
        .toString()
}

/**
 * The cockpit's network side: blocking, so call it off the screen's thread. [get] fetches a URL's body (null when it doesn't
 * answer); [post] sends a JSON body and says whether it was taken. The phone and the desktop hand in their own.
 */
class CockpitFeeds(
    private val get: (url: String) -> String?,
    private val post: (url: String, json: String) -> Boolean,
) {
    @Volatile private var answered: String? = null

    /** The machines herd last named: a nudge only goes to one of them, never to a name from anywhere else. */
    @Volatile private var known: Set<String> = emptySet()

    /** herd from the first reporter that answers, starting with the last one that did. */
    fun herd(): HerdSnapshot? {
        val order = listOfNotNull(answered) + COCKPIT_HERD_HOSTS.filter { it != answered }
        for (host in order) {
            val snapshot = runCatching { herdSnapshot(get("http://$host:$COCKPIT_HERD_PORT/v1/herd")) }.getOrNull() ?: continue
            answered = host
            known = snapshot.machines.map { it.name }.toSet()
            return snapshot
        }
        return null
    }

    fun pocket(): List<PocketItem> = pocketItems(runCatching { get("http://$COCKPIT_POCKET_HOST:$COCKPIT_TAILSHARE_PORT/v1/pocket") }.getOrNull())

    /** A nudge to [session] on [machine] (a tailnet name herd gave), by agent mail; false when it wasn't taken. */
    fun nudge(machine: String, session: String): Boolean {
        if (!Regex("[a-z0-9][a-z0-9-]{0,62}").matches(machine) || machine !in known) return false
        val body = nudgeMessage(session) ?: return false
        return runCatching { post("http://$machine:$COCKPIT_TAILSHARE_PORT/v1/msg", body) }.getOrDefault(false)
    }
}

/** The feeds over plain HTTP on the tailnet, as the phone's own herd client asks: 3 s to connect, 20 s to answer, 4 MB at most. */
fun cockpitFeeds(): CockpitFeeds = CockpitFeeds(
    get = { url ->
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 20_000
            if (connection.responseCode != 200) null else connection.inputStream.use { String(it.readNBytes(4 shl 20)) }
        } finally {
            connection.disconnect()
        }
    },
    post = { url, json ->
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray()) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    },
)
