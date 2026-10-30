package dev.omalauncher.desktop

import dev.omalauncher.assist.MacMachine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The update's hands on this computer (Updates.kt decides): the build read and fetched over the runners Soma already has
 * keys for ([machines], what the keys thread last read: never a Keychain read of its own), this computer's runner
 * installed by its own `oma-runner install`, and the app swapped by a helper that outlives this Soma.
 */
class DesktopUpdateHands(
    private val machines: () -> List<MacMachine>,
    private val dir: File,
    private val quitting: () -> Unit,
    /** The Soma.app to swap, when not the one this runs from (the walk, UpdateWalkTest). */
    private val app: File? = null,
) : UpdateHands {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val home = File(System.getProperty("user.home"))
    private val installed = File(home, ".local/bin/oma-runner")
    private val before = File(dir, "runner-before")
    private val self by lazy { Mesh.selfName() }

    /** This computer's own runner: the key named for this machine, or the one at this machine's Tailscale address. */
    private fun local(): MacMachine? {
        val all = machines()
        all.firstOrNull { it.id == self }?.let { return it }
        val address = TailscaleWhois.address() ?: return null
        return all.firstOrNull { runCatching { URI(it.config.url).host }.getOrNull() == address }
    }

    private fun base(m: MacMachine) = m.config.url.trimEnd('/')

    override fun latest(): List<Release> = machines().mapNotNull { m ->
        val (code, body) = runnerGet("${base(m)}/release", m.config.key)
        if (code == 200) Release.parse(body, m.id) else null
    }

    override fun fetch(release: Release, file: ReleaseFile, into: File): String? = fetchFrom("files", release, file, into)

    override fun fetchEngine(release: Release, file: ReleaseFile, into: File): String? = fetchFrom("engine", release, file, into)

    /** GET /release/[kind]/NAME from the runner that served [release], into [into] (through a .part file, renamed when whole). */
    private fun fetchFrom(kind: String, release: Release, file: ReleaseFile, into: File): String? {
        val m = machines().firstOrNull { it.id == release.runner } ?: return "Soma has no key for ${release.runner}'s runner any more"
        val target = File(into, file.file)
        val part = File(into, file.file + ".part")
        return runCatching {
            val c = URI("${base(m)}/release/$kind/${file.file}").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 4_000; c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer ${m.config.key}")
            if (c.responseCode != 200) return "its runner answered ${c.responseCode} for ${file.file}"
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (!part.renameTo(target)) return "${target.path} couldn't be written"
            null
        }.getOrElse { "${it.message ?: it.javaClass.simpleName} while fetching ${file.file}" }
    }

    override fun hold(): List<String>? {
        val m = local() ?: return null
        val (code, body) = runnerPost("${base(m)}/update/hold", m.config.key, """{"on": true}""")
        if (code == 200) return runCatching { JSONObject(body).getJSONArray("busy").let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
        if (code != 404) return null
        // A runner from before the hold (the first update onto it): its chats working or waiting, asked as they stand.
        val (listed, threads) = runnerGet("${base(m)}/threads", m.config.key)
        if (listed != 200) return null
        return runCatching {
            val all = JSONObject(threads).getJSONArray("threads")
            (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("status") in setOf("working", "waiting") }.map { it.optString("name").ifEmpty { it.optString("id") } }
        }.getOrNull()
    }

    override fun unhold() {
        val m = local() ?: return
        runnerPost("${base(m)}/update/hold", m.config.key, """{"on": false}""")
    }

    override fun runnerBuild(): String? {
        val m = local() ?: return null
        val (code, body) = runnerGet("${base(m)}/health", m.config.key)
        return if (code == 200) runCatching { JSONObject(body).optString("build").takeIf { it.isNotEmpty() && it != "null" } }.getOrNull() else null
    }

    /** Where the staged app is: the unpacked Soma.app on the Mac, the new jar beside the running one on Linux. */
    private fun staged(release: Release): File =
        if (mac) File(dir, "${release.commit}/app/Soma.app") else File(runningJar()?.parentFile ?: File(home, "Soma/builds"), "Soma-${platformKey()}-${release.commit}.jar")

    override fun stageApp(artifact: File, release: Release): String? {
        val target = staged(release)
        if (mac) {
            runningApp() ?: return "this Soma isn't running from Soma.app (started from a build folder?)"
            target.parentFile.deleteRecursively()
            target.parentFile.mkdirs()
            run("ditto", "-x", "-k", artifact.path, target.parentFile.path)?.let { return "the zip didn't unpack: $it" }
            if (!target.isDirectory) return "the zip had no Soma.app in it"
            run("codesign", "--verify", "--deep", "--strict", target.path)?.let { return "its signature doesn't verify: $it" }
            // Signed by the same identity as this one, so the Keychain's "Always Allow" for the runner keys holds.
            val mine = team(runningApp()!!)
            val theirs = team(target)
            return if (mine != null && theirs != mine) "it is signed by ${theirs ?: "no team (an ad hoc build)"}, not $mine as this Soma is" else null
        }
        runningJar() ?: return "this Soma isn't running from a jar"
        return runCatching { artifact.copyTo(target, overwrite = true); null }.getOrElse { "${target.path} couldn't be written: ${it.message}" }
    }

    override fun swapRunner(tarball: File, release: Release): String? {
        val python = runnerPython() ?: return "no python 3.11 or later was found to install it with"
        val unpacked = File(tarball.parentFile, "runner").apply { deleteRecursively(); mkdirs() }
        run("tar", "-xzf", tarball.path, "-C", unpacked.path)?.let { return "the runner's tarball didn't unpack: $it" }
        // The installed copy kept first, with its services module, so it can put itself back (restoreRunner).
        before.deleteRecursively()
        before.mkdirs()
        for (name in listOf("oma-runner", "soma_services.py", "oma-runner.build")) File(installed.parentFile, name).takeIf { it.isFile }?.copyTo(File(before, name), overwrite = true)
        run(python, File(unpacked, "oma-runner").path, "install", timeout = 120)?.let { return restoredAfter("its install said: $it") }
        if (!runnerOn(release.commit)) return restoredAfter("it didn't come back on ${release.commit} within a minute")
        return null
    }

    private fun restoredAfter(why: String): String = why + (restoreRunner()?.let { "; putting the old one back failed too: $it" } ?: "")

    override fun restoreRunner(): String? {
        val copy = File(before, "oma-runner").takeIf { it.isFile } ?: return "no copy of the old runner was kept"
        val python = runnerPython() ?: return "no python to install it with"
        run(python, copy.path, "install", timeout = 120)?.let { return it }
        val was = File(before, "oma-runner.build").takeIf { it.isFile }?.readText()?.trim()
        return if (runnerOn(was)) null else "it didn't answer${was?.let { " on $it" } ?: ""} within a minute"
    }

    /** Whether this computer's runner answers within a minute, on [commit] when one is named. */
    private fun runnerOn(commit: String?): Boolean {
        repeat(60) {
            Thread.sleep(1_000)
            val m = local() ?: return@repeat
            val (code, body) = runnerGet("${base(m)}/health", m.config.key, timeout = 3_000)
            if (code == 200 && (commit == null || runCatching { JSONObject(body).optString("build") }.getOrNull() == commit)) return true
        }
        return false
    }

    /** The python the installed runner runs on (its launchd job or systemd unit), else one the runner accepts. */
    internal fun runnerPython(): String? {
        val fromService = runCatching {
            if (mac) {
                val plist = listOf(File("/Library/LaunchDaemons/dev.omalauncher.runner.plist"), File(home, "Library/LaunchAgents/dev.omalauncher.runner.plist")).first { it.isFile }
                output("plutil", "-extract", "ProgramArguments.0", "raw", "-o", "-", plist.path)?.trim()
            } else {
                File(home, ".config/systemd/user/oma-runner.service").readLines().first { it.startsWith("ExecStart=") }.removePrefix("ExecStart=").substringBefore(' ')
            }
        }.getOrNull()
        return (listOfNotNull(fromService) + listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3")).firstOrNull { File(it).canExecute() }
    }

    override fun startSwap(release: Release): String? {
        val script = File(dir, "swap.sh").apply { writeText(SWAP_SCRIPT); setExecutable(true) }
        val pid = ProcessHandle.current().pid().toString()
        val words = if (mac) {
            val app = runningApp() ?: return "this Soma isn't running from Soma.app"
            listOf("perl", "-MPOSIX", "-e", "POSIX::setsid(); exec @ARGV", "/bin/sh", script.path, "mac", pid, app.path, staged(release).path, release.commit, SomaBuild.own.commit, dir.path)
        } else {
            val jar = runningJar() ?: return "this Soma isn't running from a jar"
            listOf("setsid", "/bin/sh", script.path, "linux", pid, jar.path, staged(release).path, release.commit, SomaBuild.own.commit, dir.path) + javaCommand()
        }
        return runCatching {
            File(dir, "result.json").delete()
            ProcessBuilder(words).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "swap.log"))).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            null
        }.getOrElse { "the helper didn't start: ${it.message}" }
    }

    override fun settled() {
        // On Linux the links and the login entry name the jar: they follow the new one.
        if (mac) return
        runCatching { Login.registerLinks() }
        if (Login.enabled()) runCatching { Login.install() }
    }

    override fun quit() = quitting()

    /** The Soma.app this Soma runs from, or null (a build folder, the jar). */
    private fun runningApp(): File? = app ?: ProcessHandle.current().info().command().orElse("").takeIf { it.endsWith(".app/Contents/MacOS/Soma") }
        ?.let { File(it).parentFile.parentFile.parentFile }

    private fun runningJar(): File? = runCatching { File(DesktopUpdateHands::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()?.takeIf { it.isFile && it.name.endsWith(".jar") }

    /** The java and its options this Soma was started with, up to -jar, so the new jar starts the same way. */
    private fun javaCommand(): List<String> {
        val info = ProcessHandle.current().info()
        val args = info.arguments().orElse(emptyArray()).toList()
        return listOf(info.command().orElse("java")) + args.takeWhile { it != "-jar" }
    }

    /** The TeamIdentifier an app is signed with, or null (ad hoc, or unsigned). */
    private fun team(app: File): String? = runCatching {
        val p = ProcessBuilder("codesign", "-dv", app.path).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }.lines().firstOrNull { it.startsWith("TeamIdentifier=") }?.substringAfter('=')?.takeIf { it != "not set" }
    }.getOrNull()

    /** Runs a command; null when it succeeded, else what it said (its last lines). */
    private fun run(vararg words: String, timeout: Long = 60): String? = runCatching {
        val p = ProcessBuilder(*words).redirectErrorStream(true).start()
        val said = StringBuilder()
        val reader = kotlin.concurrent.thread(isDaemon = true) { said.append(p.inputStream.bufferedReader().readText()) }
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) { p.destroyForcibly(); return "${words[0]} took longer than $timeout s" }
        reader.join(2_000)
        if (p.exitValue() == 0) null else "exit ${p.exitValue()}: " + said.toString().trim().lines().takeLast(3).joinToString(" / ")
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun output(vararg words: String): String? = runCatching {
        val p = ProcessBuilder(*words).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }.takeIf { p.exitValue() == 0 }
    }.getOrNull()
}

/**
 * The swap, done after Soma quits, in a session of its own (so the launchd job or terminal that started Soma ending
 * doesn't end it). It waits for the old Soma to go, puts the new app in place (the old one kept: Soma.app.prev on the Mac,
 * the old jar beside the new on Linux), starts it, and waits a minute for it to say it started. If it doesn't, the old
 * one goes back and starts, and result.json says why: the old Soma reads it and puts the runner back too.
 * Args: mode pid old new to from dir [java and its options, Linux].
 */
internal val SWAP_SCRIPT = """#!/bin/sh
mode=${'$'}1 pid=${'$'}2 old=${'$'}3 new=${'$'}4 to=${'$'}5 from=${'$'}6 dir=${'$'}7
shift 7
result="${'$'}dir/result.json"
say() { printf '{"ok": %s, "from": "%s", "to": "%s", "step": "%s", "error": "%s", "runnerSwapped": true}\n' "${'$'}1" "${'$'}from" "${'$'}to" "${'$'}2" "${'$'}3" > "${'$'}result"; echo "${'$'}(date) ${'$'}1 ${'$'}2 ${'$'}3"; }
started() { n=0; while [ ${'$'}n -lt "${'$'}{SOMA_SWAP_WAIT:-60}" ]; do [ "${'$'}(cat "${'$'}dir/started" 2>/dev/null)" = "${'$'}to" ] && return 0; sleep 1; n=${'$'}((n+1)); done; return 1; }
# The canvas service (soma --serve) runs from the app too: restarted on the new one, only when it is this very app's.
serve() { grep -qF "${'$'}old/" "${'$'}HOME/Library/LaunchAgents/dev.omalauncher.soma.serve.plist" 2>/dev/null && launchctl kickstart -k "gui/${'$'}(id -u)/dev.omalauncher.soma.serve"; }
echo "${'$'}(date) updating ${'$'}from -> ${'$'}to (${'$'}mode); waiting for Soma ${'$'}pid to quit"
n=0
while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.5; n=${'$'}((n+1)); [ ${'$'}n -gt 240 ] && { say false quit "the old Soma did not quit within two minutes"; exit 1; }; done
rm -f "${'$'}dir/started"
# Up for a while: a Soma that says it started and then dies within seconds didn't start either.
stays() { sleep "${'$'}{SOMA_SWAP_STAY:-10}"; if [ "${'$'}mode" = mac ]; then pgrep -fx "${'$'}old/Contents/MacOS/Soma" >/dev/null; else kill -0 "${'$'}newpid" 2>/dev/null; fi; }
if [ "${'$'}mode" = mac ]; then
  # The real path (a /var that is /private/var), as the process list shows the app it runs.
  old="${'$'}(cd "${'$'}(dirname "${'$'}old")" && pwd -P)/${'$'}(basename "${'$'}old")"
  rm -rf "${'$'}old.prev"
  mv "${'$'}old" "${'$'}old.prev" || { say false app "the old app could not be moved aside"; open "${'$'}old"; exit 1; }
  if ! mv "${'$'}new" "${'$'}old"; then mv "${'$'}old.prev" "${'$'}old"; say false app "the new app could not be moved into place"; open "${'$'}old"; exit 1; fi
  serve
  say true app ""
  open "${'$'}old"
  if ! started || ! stays; then
    pkill -fx "${'$'}old/Contents/MacOS/Soma"
    sleep 2
    rm -rf "${'$'}old"; mv "${'$'}old.prev" "${'$'}old"
    serve
    say false start "the new Soma did not start, or did not stay up; the old app is back"
    open "${'$'}old"
    exit 1
  fi
else
  say true app ""
  "${'$'}@" -jar "${'$'}new" </dev/null >/dev/null 2>&1 &
  newpid=${'$'}!
  if ! started || ! stays; then
    kill ${'$'}newpid 2>/dev/null
    say false start "the new Soma did not start, or did not stay up; the old jar runs again"
    "${'$'}@" -jar "${'$'}old" </dev/null >/dev/null 2>&1 &
    exit 1
  fi
fi
echo "${'$'}(date) done: ${'$'}to started"
"""
