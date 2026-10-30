package dev.omalauncher.desktop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/*
 * `soma --mesh` (Boss, 2026-10-28: "no more partial built lanes, we build two-way roads"): every Soma should see and start
 * chats on every runner. This Soma reads each runner it has a key for (whether the key is let in, and the names that
 * runner lets in: GET /devices), finds the tailnet computers that run a runner it has no key for, and says every missing
 * leg by name, with the command that makes it. A leg is a Soma (by its machine's Tailscale name) to a runner. The
 * decisions are [meshGaps]; the asking is [Mesh.run].
 */

/**
 * What this Soma knows of one runner: whether its key is let in, and who it lets in (null: unknown, no key here).
 * [awaiting]: a phone on the tailnet whose runner comes with its next bundle (A, 2026-10-29): an expected leg, not a gap.
 */
data class RunnerSeen(val name: String, val keyed: Boolean, val accepted: Boolean, val lets: Set<String>?, val awaiting: Boolean = false)

/** One missing leg: [soma]'s Soma can't use [runner], and [fix] is what makes it. */
data class MeshGap(val soma: String, val runner: String, val fix: String)

private fun pairOn(runner: String, soma: String) = "on $runner: OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair $soma"

/**
 * The missing legs among [runners] (every runner on the tailnet, as [me]'s Soma sees them): a runner [me] has no key for,
 * or whose key isn't let in; and any computer that runs a runner (so it should have a Soma too) that a runner [me] can
 * read doesn't let in. A runner's own machine counts. The phone is never a Soma here, so it isn't asked of runners.
 */
fun meshGaps(me: String, runners: List<RunnerSeen>): List<MeshGap> {
    val computers = (runners.filter { !it.awaiting }.map { it.name } + me).toSortedSet()
    val gaps = mutableListOf<MeshGap>()
    for (runner in runners.filter { !it.awaiting }.sortedBy { it.name }) {
        if (!runner.keyed || !runner.accepted) gaps += MeshGap(me, runner.name, pairOn(runner.name, me))
        val lets = runner.lets ?: continue
        for (soma in computers) if (soma != me && soma !in lets) gaps += MeshGap(soma, runner.name, pairOn(runner.name, soma))
    }
    return gaps.distinctBy { it.soma to it.runner }
}

/** The matrix as it prints: a row per Soma, a column per runner; yes, MISSING, or ? where this Soma can't read that runner. */
fun meshTable(me: String, runners: List<RunnerSeen>): String {
    val columns = runners.sortedBy { it.name }
    val rows = (columns.filter { !it.awaiting }.map { it.name } + me).toSortedSet()
    val width = (rows.map { it.length + 9 } + listOf(16)).max()
    fun wide(r: RunnerSeen) = maxOf(r.name.length + 2, if (r.awaiting) 17 else 10)
    val header = "Soma \\ runner".padEnd(width) + columns.joinToString("") { it.name.padEnd(wide(it)) }
    val lines = rows.map { soma ->
        (soma + if (soma == me) " (this)" else "").padEnd(width) + columns.joinToString("") { r ->
            val cell = when {
                r.awaiting -> "awaiting bundle"
                soma == me -> if (r.keyed && r.accepted) "yes" else "MISSING"
                r.lets == null -> "?"
                soma in r.lets -> "yes"
                else -> "MISSING"
            }
            cell.padEnd(wide(r))
        }
    }
    return (listOf(header) + lines).joinToString("\n")
}

/** The name a proof thread goes by (`soma --mesh --prove`), so `--read` on another Soma can find it: who made it, and when. */
const val MESH_PROOF = "Mesh proof from "

object Mesh {
    /**
     * `soma --mesh --prove` (A, 2026-10-29: "prove each with a real create plus a read of that thread from the other Soma"):
     * a thread made on every runner this Soma has a key for, in the first folder that runner allows, named [MESH_PROOF] and
     * this machine; nothing is sent to it, so no agent runs. Says each thread's id; 0 when every runner made one.
     */
    fun prove(say: (String) -> Unit): Int {
        val me = tailnet().first ?: SomaHome.machine
        val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
        var failed = 0
        for (m in RunnerKeys.read()) {
            val client = dev.omalauncher.assist.MacRunnerClient(m.config, m.id)
            // The runner says which folders it allows when asked for one it doesn't: the first of them.
            val roots = (client.makeThread("x", "/nonexistent-mesh-probe") as? dev.omalauncher.assist.MacAnswer.Refused)?.roots.orEmpty()
            val made = roots.firstOrNull()?.let { client.makeThread("$MESH_PROOF$me $stamp", it, internal = true) }
            val thread = (made as? dev.omalauncher.assist.MacAnswer.Ok)?.value
            if (thread != null) say("made on ${m.id}: \"${thread.name}\" ${thread.id}") else { failed++; say("FAILED on ${m.id}: ${made ?: "it named no folder to make one in"}") }
        }
        return if (failed == 0) 0 else 1
    }

    /**
     * `soma --mesh --list`: every thread on every runner this Soma reaches, by name and how it stands (a phone runner's
     * sessions, Phone A to D, among them), read from here: the read half of a new leg's proof where a thread can't be made.
     */
    fun list(say: (String) -> Unit): Int = read(say, all = false) { true }

    /** `soma --mesh --read`: the proof threads on every runner this Soma reaches, whoever made them, read from here. */
    fun read(say: (String) -> Unit): Int = read(say, all = true) { it.name.startsWith(MESH_PROOF) }

    private fun read(say: (String) -> Unit, all: Boolean, which: (dev.omalauncher.assist.MacThread) -> Boolean): Int {
        var failed = 0
        for (m in RunnerKeys.read()) {
            when (val listed = dev.omalauncher.assist.MacRunnerClient(m.config, m.id).threads(all)) {
                is dev.omalauncher.assist.MacAnswer.Ok -> {
                    val shown = listed.value.filter(which)
                    say("${m.id}: ${shown.size} thread${if (shown.size == 1) "" else "s"}" + shown.joinToString("") { "\n  ${it.name} (${it.id}, ${it.status.name.lowercase()})" })
                }
                else -> { failed++; say("FAILED to read ${m.id}: $listed") }
            }
        }
        return if (failed == 0) 0 else 1
    }


    private const val PORT = 8478

    /** One authenticated GET (or a bare one with no [key]): its status and body; status 0 when nothing answered. */
    private fun get(url: String, key: String?): Pair<Int, String> = runCatching {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 4_000; c.readTimeout = 8_000
        if (key != null) c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
    }.getOrDefault(0 to "")

    /** This machine's Tailscale name, its online peers (name to address), and which of them are phones, from `tailscale status --json`. */
    /** This machine's Tailscale name ([tailnet]'s first): what a runner pairs it by; null when Tailscale can't be asked. */
    fun selfName(): String? = tailnet().first

    private fun tailnet(): Triple<String?, Map<String, String>, Set<String>> = runCatching {
        val p = ProcessBuilder(TailscaleWhois.find(), "status", "--json").apply { environment().putIfAbsent("TERM", "dumb") }.start()
        val status = JSONObject(p.inputStream.bufferedReader().readText()).also { p.waitFor() }
        fun nameOf(o: JSONObject) = o.optString("DNSName").substringBefore('.').lowercase().ifEmpty { o.optString("HostName").lowercase() }
        val peers = status.optJSONObject("Peer") ?: JSONObject()
        val online = peers.keySet().map { peers.getJSONObject(it) }.filter { it.optBoolean("Online") }
        Triple(nameOf(status.getJSONObject("Self")), online.mapNotNull { o ->
            val address = (0 until (o.optJSONArray("TailscaleIPs")?.length() ?: 0)).map { o.getJSONArray("TailscaleIPs").getString(it) }.firstOrNull { '.' in it }
            address?.let { nameOf(o) to it }
        }.toMap(), online.filter { it.optString("OS").equals("android", ignoreCase = true) }.map { nameOf(it) }.toSet())
    }.getOrDefault(Triple(null, emptyMap(), emptySet()))

    /** Prints the matrix and each missing leg with its fix; 0 when there are none, 1 when there are, 2 when it can't tell. */
    fun run(say: (String) -> Unit): Int {
        val (self, peers, phones) = tailnet()
        val me = self ?: return 2.also { say("Tailscale can't be asked here, so the roads can't be checked.") }
        val keys = RunnerKeys.read().associateBy { it.id }
        val seen = mutableListOf<RunnerSeen>()
        // Every runner on the tailnet (it answers /health, asking for a key), and every runner this Soma has a key for.
        val addresses = peers + listOfNotNull(TailscaleWhois.address()?.let { me to it })
        for (name in (addresses.keys + keys.keys).toSortedSet()) {
            val machine = keys[name]
            if (machine == null) {
                val address = addresses[name] ?: continue
                if (get("http://$address:$PORT/health", null).first in setOf(200, 401, 403)) seen += RunnerSeen(name, keyed = false, accepted = false, lets = null)
                // The phone with no runner yet: it comes with the phone's next bundle, so its leg is expected, not missing.
                else if (name in phones) seen += RunnerSeen(name, keyed = false, accepted = false, lets = null, awaiting = true)
                continue
            }
            val base = machine.config.url.trimEnd('/')
            val health = get("$base/health", machine.config.key).first
            if (health == 0) { say("$name: its runner doesn't answer at $base (asleep or off the tailnet), so its legs aren't checked."); continue }
            val lets = get("$base/devices", machine.config.key).takeIf { it.first == 200 }
                ?.let { (_, body) -> runCatching { JSONObject(body).getJSONArray("devices").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() }
            seen += RunnerSeen(name, keyed = true, accepted = health == 200, lets = lets)
        }
        if (seen.isEmpty()) return 2.also { say("No runner answers on the tailnet from here.") }
        say(meshTable(me, seen))
        val unknown = seen.filter { it.keyed && it.accepted && it.lets == null }.map { it.name }
        if (unknown.isNotEmpty()) say("(${unknown.joinToString()}: an older runner that doesn't say who it lets in; its column shows only this Soma.)")
        // A key Taildropped here and waiting to be moved in (Soma never takes keys from ~/Inbox or ~/Downloads): its exact move.
        val home = java.io.File(System.getProperty("user.home"))
        val waiting = seen.filter { !it.keyed && !it.awaiting }.associate { r ->
            r.name to listOf("Inbox", "Downloads").flatMap { d -> java.io.File(home, d).listFiles().orEmpty().filter { it.name.startsWith("soma-runner-${r.name}") && it.name.endsWith(".json") } }.sortedBy { it.lastModified() }
        }.filterValues { it.isNotEmpty() }
        val gaps = meshGaps(me, seen).map { g ->
            val landed = waiting[g.runner]?.takeIf { g.soma == me } ?: return@map g
            g.copy(fix = "key waiting in ~/${landed.last().parentFile.name}: mv \"${landed.last().path}\" ${java.io.File(SomaHome.dir, "runners").path}/soma-runner-${g.runner}.json" +
                landed.dropLast(1).joinToString("") { "; rm \"${it.path}\" (an older, replaced key)" })
        }
        seen.filter { it.awaiting }.forEach { say("${it.name}: its phone runner comes with its next bundle; its legs are expected, not missing.") }
        // A runner that can't say who it lets in (an older one) may hide another Soma's missing leg: never "none" then.
        val blind = seen.filter { !it.awaiting && it.lets == null }.map { it.name }
        if (gaps.isEmpty()) return 0.also { say(if (blind.isEmpty()) "no missing legs" else "no missing legs from this Soma; the others' legs to ${blind.joinToString()} can't be seen from here (run soma --mesh on each, or update those runners)") }
        say("${gaps.size} missing leg${if (gaps.size == 1) "" else "s"}, each made by pairing on the runner it leads to:")
        for (g in gaps) say("  ${g.soma}'s Soma -> ${g.runner}: ${g.fix}")
        return 1
    }
}
