package dev.omalauncher.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import kotlin.concurrent.thread

/*
 * The update pill (Boss approved on Soma Canvas, soma-desktop/Updates, 2026-10-29): when the Mac Pro has published a newer
 * desktop build (tools/soma-release: only after every suite passed), the top bar shows a pill. Nothing changes until he
 * taps it and then "update and restart". The app and this computer's runner move together, on one commit; the runner
 * restarts only when no chat is working on it (its /health says 0). The build comes over the runners he already paired,
 * with the keys Soma already holds; each file is checked against the record's sha256 before anything is swapped. Anything
 * that goes wrong keeps the old version and says why, in words an agent can act on. The decisions are here; the hands
 * (fetching, the runner's own install, the swap helper) are [DesktopUpdateHands].
 */

/** This Soma's own build: the commit it was built from and when that was committed (soma-build.properties, made at build). */
data class SomaBuild(val commit: String, val committed: Long) {
    companion object {
        val own: SomaBuild by lazy { read(SomaBuild::class.java.getResourceAsStream("/soma-build.properties")?.use { it.readBytes().decodeToString() }) }

        fun read(text: String?): SomaBuild {
            val p = Properties().apply { text?.let { load(it.reader()) } }
            return SomaBuild(p.getProperty("commit")?.trim().orEmpty().ifEmpty { "unknown" }, p.getProperty("committed")?.trim()?.toLongOrNull() ?: 0)
        }
    }
}

/** One file of a published build, as its record names it. */
data class ReleaseFile(val file: String, val sha256: String, val size: Long)

/** The published desktop build, as [runner] (the runner it was read from) serves it at GET /release. */
data class Release(val commit: String, val committed: Long, val notes: List<String>, val artifacts: Map<String, ReleaseFile>, val runner: String) {
    companion object {
        private val COMMIT = Regex("[0-9a-f]{7,40}")
        private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
        private val SHA = Regex("[0-9a-f]{64}")

        /** The record in a GET /release answer, or null for anything that isn't a whole one. */
        fun parse(body: String, runner: String): Release? = runCatching {
            val r = JSONObject(body).getJSONObject("release")
            val commit = r.getString("commit").takeIf { COMMIT.matches(it) } ?: return null
            val files = r.getJSONObject("artifacts")
            val artifacts = files.keySet().associateWith { key ->
                val a = files.getJSONObject(key)
                ReleaseFile(a.getString("file").takeIf { FILE.matches(it) } ?: return null, a.getString("sha256").takeIf { SHA.matches(it) } ?: return null, a.getLong("size"))
            }
            val notes = r.optJSONArray("notes")?.let { n -> (0 until n.length()).map { n.getString(it) } }.orEmpty()
            Release(commit, r.getLong("committed"), notes, artifacts, runner)
        }.getOrNull()
    }
}

/** Which of a build's files is this computer's app: macos-arm64, macos-x64, linux-x64 or linux-arm64. */
fun platformKey(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
    val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
    return (if (os.lowercase().contains("mac")) "macos-" else "linux-") + cpu
}

/** Whether [release] is offered to a Soma built as [own] on [platform]: a newer commit, with this computer's app and the runner in it. */
fun offered(own: SomaBuild, release: Release?, platform: String): Boolean =
    release != null && release.commit != own.commit && release.committed > own.committed && platform in release.artifacts && "runner" in release.artifacts

/** The newest of the records the runners serve (they should all be the Mac Pro's; the newest commit wins if not). */
fun newest(found: List<Release>): Release? = found.maxByOrNull { it.committed }

/** Whether [file]'s bytes are the ones [expected] names: its size, then its sha256. */
fun matches(file: File, expected: ReleaseFile): Boolean {
    if (!file.isFile || file.length() != expected.size) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val block = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            digest.update(block, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256
}

/** When a build's commit was made, as the sheet says it: "Oct 29, 16:00". */
fun whenCommitted(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).format(Instant.ofEpochSecond(seconds).atZone(zone))

/** The steps an update goes through, as the updating card lists them. */
val UPDATE_STEPS = listOf("fetch the build", "check its sha256", "swap the app and the runner", "restart the runner, then Soma")

/** Where an update stands. The pill shows for every state but [None]. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Offer(val release: Release) : UpdateState
    /** Waiting for [chats] (their names) on this computer's runner to finish their turns. */
    data class Waiting(val release: Release, val chats: List<String>) : UpdateState
    /** Doing [UPDATE_STEPS], at [step]. */
    data class Updating(val release: Release, val step: Int) : UpdateState
    data class Updated(val commit: String, val note: String? = null) : UpdateState
    /** Nothing changed (or what changed was put back): [kept] still runs. [details] is for an agent, copied whole. */
    data class Failed(val kept: String, val reason: String, val details: String, val release: Release?) : UpdateState
}

/** What an update needs done, by the computer (DesktopUpdateHands) or by a test. Each says what went wrong as a sentence, or null. */
interface UpdateHands {
    /** The published build, read from every runner this Soma has a key for. */
    fun latest(): List<Release>

    /** [file] of [release], fetched into [into]. */
    fun fetch(release: Release, file: ReleaseFile, into: File): String?

    /**
     * This computer's runner held for the update (POST /update/hold: no new turn starts, agent mail waits) and the names of
     * the chats with work still in it; empty when it can restart with nothing cut off. Null when it can't be asked.
     */
    fun hold(): List<String>?

    /** The hold let go (cancelled, or failed before the restart): turns start again. */
    fun unhold() {}

    /** The commit this computer's runner says it runs (/health build), or null when it says none or can't be asked. */
    fun runnerBuild(): String? = null

    /** The app, unpacked (and on the Mac its signature checked) where the swap takes it from; nothing running changes. */
    fun stageApp(artifact: File, release: Release): String?

    /** This computer's runner, installed from [tarball] and back up on [release]'s commit; on failure the old one is put back. */
    fun swapRunner(tarball: File, release: Release): String?

    /** The old one put back, after an app swap that didn't take ([UpdateState.Failed] at the next start). */
    fun restoreRunner(): String?

    /** The helper that swaps the app once this Soma has quit, starts the new one and keeps the old one if it doesn't start. */
    fun startSwap(release: Release): String?

    /** This Soma ends; the helper takes over. */
    fun quit()

    /** After a new Soma started: whatever names the app's own file follows it (Linux's links and login entry). */
    fun settled() {}
}

/** The update, from the check to the restart (and, at the next start, what came of it). [dir] holds its files and its result. */
class Updater(
    private val hands: UpdateHands,
    private val dir: File,
    val own: SomaBuild = SomaBuild.own,
    private val platform: String = platformKey(),
    private val machine: String = SomaHome.machine,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    var state by mutableStateOf<UpdateState>(UpdateState.None)
        private set

    /** Whether the sheet (what changed, or how it went) is open. */
    var open by mutableStateOf(false)

    @Volatile private var cancelled = false
    @Volatile private var busy = false
    private val result get() = File(dir, "result.json")

    /** Asks the runners for the published build; the pill shows when it is newer than this one. Leaves a running update alone. */
    fun check() {
        if (busy) return
        val release = newest(hands.latest())
        val now = state
        if (now is UpdateState.Waiting || now is UpdateState.Updating || now is UpdateState.Updated) return
        // "didn't update" stays until he tries again; one about the runner and app apart takes the build to try with.
        if (now is UpdateState.Failed) {
            if (now.release == null && offered(own, release, platform)) state = now.copy(release = release)
            return
        }
        state = if (offered(own, release, platform)) UpdateState.Offer(release!!) else UpdateState.None
    }

    /**
     * At the start: what the last update came to, said once. A new Soma marks itself started, which the swap helper waits
     * for. With nothing to say, the runner and this Soma are compared: apart (an update cut off by a crash or power), it says so.
     */
    fun reportAtStart() {
        markStarted()
        val said = runCatching { JSONObject(result.readText()) }.getOrNull()
        if (said == null) {
            val runner = hands.runnerBuild()
            if (runner != null && runner != own.commit) state = failed("this computer's runner is on $runner and Soma on ${own.commit}: an update stopped partway. Try again to put both on the newest build.", "apart", null)
            return
        }
        result.delete()
        settle(said)
    }

    /** Tells the swap helper this Soma is up (it waits a minute for it), before anything that can wait on the Keychain. */
    fun markStarted() {
        File(dir, "started").apply { parentFile.mkdirs(); writeText(own.commit + "\n") }
    }

    /** What the helper's result says, taken in: updated, or failed (the runner put back when it had moved). */
    private fun settle(said: JSONObject) {
        val to = said.optString("to")
        if (said.optBoolean("ok") && to == own.commit) {
            state = UpdateState.Updated(own.commit)
            hands.settled()
            return
        }
        val reason = said.optString("error").ifEmpty { "the new Soma didn't start" }
        if (!said.optBoolean("runnerSwapped")) return run { state = failed("$reason.", said.optString("step"), null, to) }
        // The runner was already on the new commit: it goes back too, once nothing would be cut off. Said while it waits.
        state = failed("$reason; the runner goes back to ${own.commit} as soon as no chat is working.", said.optString("step"), null, to)
        val back = waitForQuiet(null) ?: hands.restoreRunner()
        hands.unhold()
        state = failed("$reason; " + (back?.let { "the runner couldn't be put back: $it" } ?: "the runner is back on ${own.commit} too") + ".", said.optString("step"), null, to)
    }

    /** A state set directly: the walk's pictures of each card (UpdatesTest.shots). */
    internal fun forTest(shown: UpdateState) { state = shown }

    /** Leaves the waiting, before anything was swapped. */
    fun cancel() {
        cancelled = true
    }

    /** The sheet closed: "updated" has been read, so the pill goes; "didn't update" stays until he tries again. */
    fun close() {
        open = false
        if (state is UpdateState.Updated) state = UpdateState.None
    }

    /** "update and restart" (or "try again"): on a thread of its own. */
    fun start(release: Release) {
        if (busy) return
        busy = true
        cancelled = false
        thread(name = "soma-update", isDaemon = true) {
            try { update(release) } catch (e: Exception) { hands.unhold(); state = failed("something unexpected: ${e.message ?: e.javaClass.simpleName}. Nothing was swapped.", "unknown", release) } finally { busy = false }
        }
    }

    /** The update itself; runs on the caller's thread (tests call it directly). */
    internal fun update(release: Release) {
        val into = File(dir, release.commit).apply { mkdirs() }
        val app = release.artifacts[platform] ?: return run { state = failed("the build has no app for $platform. Nothing was swapped.", "fetch", release) }
        val runner = release.artifacts["runner"] ?: return run { state = failed("the build has no runner. Nothing was swapped.", "fetch", release) }
        state = UpdateState.Updating(release, 0)
        for (file in listOf(app, runner)) hands.fetch(release, file, into)?.let { return run { state = failed("the build couldn't be fetched from ${release.runner}: $it. Nothing was swapped.", "fetch", release) } }
        state = UpdateState.Updating(release, 1)
        for (file in listOf(app, runner)) if (!matches(File(into, file.file), file)) return run { state = failed("the download's sha256 didn't match the record (${file.file}). Nothing was swapped.", "check", release) }
        state = UpdateState.Updating(release, 2)
        hands.stageApp(File(into, app.file), release)?.let { return run { state = failed("the new app couldn't be unpacked: $it. Nothing was swapped.", "stage", release) } }
        waitForQuiet(release)?.let { why ->
            hands.unhold()
            return run { state = if (cancelled) UpdateState.Offer(release) else failed("$why. Nothing was swapped.", "wait", release) }
        }
        state = UpdateState.Updating(release, 2)
        hands.swapRunner(File(into, runner.file), release)?.let {
            hands.unhold()
            return run { state = failed("the runner didn't take the new copy: $it. The old runner is back; the app wasn't touched.", "runner", release) }
        }
        state = UpdateState.Updating(release, 3)
        hands.startSwap(release)?.let { why ->
            val back = hands.restoreRunner()?.let { "; the runner couldn't be put back: $it" } ?: "; the runner was put back"
            return run { state = failed("the app swap couldn't start: $why$back.", "app", release) }
        }
        result.delete()
        hands.quit()
        // Still here: this Soma didn't quit. The helper gives up after two minutes and says so; that is taken in here.
        repeat(150) {
            pause(1_000)
            runCatching { JSONObject(result.readText()) }.getOrNull()?.let { said -> result.delete(); return settle(said) }
        }
    }

    /** Holds the runner and waits until nothing on it would be cut off (the waiting card names the chats); null then, else why it stopped. */
    private fun waitForQuiet(release: Release?): String? {
        while (true) {
            if (cancelled) return "cancelled"
            val chats = hands.hold() ?: return "this computer's runner couldn't be asked whether a chat is working"
            if (chats.isEmpty()) return null
            if (release != null) state = UpdateState.Waiting(release, chats)
            pause(3_000)
        }
    }

    private fun failed(reason: String, step: String, release: Release?, offered: String? = release?.commit): UpdateState.Failed {
        val details = listOf(
            "Soma's update didn't happen on $machine (${platform}).",
            "kept: ${own.commit}" + (offered?.let { "  offered: $it" } ?: "") + (release?.let { "  from the runner on ${it.runner}" } ?: ""),
            "step: $step",
            "why: $reason",
            "files: ${dir.path}",
            "helper log: ${File(dir, "swap.log").path}",
            "the record: GET /release on the Mac Pro's runner (tools/soma-release); try again from the pill once fixed.",
        ).joinToString("\n")
        // Kept on disk too, so an agent on this computer can read it without the window.
        runCatching { File(dir, "last-error.txt").apply { parentFile.mkdirs(); writeText(details + "\n") } }
        return UpdateState.Failed(own.commit, reason, details, release)
    }
}

/** One authenticated POST of a JSON [body] to a runner, its status and body (status 0 when nothing answered). */
internal fun runnerPost(url: String, key: String, body: String): Pair<Int, String> = runCatching {
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 4_000; c.readTimeout = 8_000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Authorization", "Bearer $key")
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write(body.toByteArray()) }
    val code = c.responseCode
    code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
}.getOrDefault(0 to "")

/** One authenticated GET of a runner, its status and body (status 0 when nothing answered). */
internal fun runnerGet(url: String, key: String, timeout: Int = 8_000): Pair<Int, String> = runCatching {
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 4_000; c.readTimeout = timeout
    c.setRequestProperty("Authorization", "Bearer $key")
    val code = c.responseCode
    code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
}.getOrDefault(0 to "")
