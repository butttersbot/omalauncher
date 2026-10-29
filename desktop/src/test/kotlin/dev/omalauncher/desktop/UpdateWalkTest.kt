package dev.omalauncher.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.omalauncher.assist.MacMachine
import dev.omalauncher.assist.MacRunnerConfig
import dev.omalauncher.ui.Pack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The update walk (-PsomaWalk=DIR), on this Mac and nothing of Boss's: a build published by the real tools/soma-release
 * from a scratch checkout, served by this branch's oma-runner on a spare port (a key of its own, never one of his), read,
 * fetched, sha-checked and unpacked by the real DesktopUpdateHands, through the pill and the sheet as he taps them; then
 * the swap helper on a stand-in Soma.app, once starting and once not. The runner's own install and quitting this Soma are
 * the two steps left to a real update: here they are recorded, not done (they would restart his real runner).
 */
@OptIn(ExperimentalTestApi::class)
class UpdateWalkTest {
    private val out = System.getProperty("soma.walk")?.takeIf { it.isNotEmpty() }?.let(::File)
    private val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/soma-release/soma-release").isFile }

    private fun sh(vararg words: String, dir: File? = null, env: Map<String, String> = emptyMap()): String {
        val p = ProcessBuilder(*words).directory(dir).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val said = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${words.joinToString(" ")}: $said" }
        return said
    }

    /** A stand-in Soma.app: its "Soma" says it started (as a real one's reportAtStart does) when it is [commit]. */
    private fun fakeApp(at: File, commit: String, starts: Boolean, said: File) {
        val macos = File(at, "Soma.app/Contents/MacOS").apply { mkdirs() }
        File(at, "Soma.app/Contents/Info.plist").writeText("""<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>
            <key>CFBundleExecutable</key><string>Soma</string><key>CFBundleIdentifier</key><string>dev.omalauncher.soma.walk</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>""")
        File(macos, "Soma").apply {
            writeText("#!/bin/sh\necho $commit >> \"${said.path}/ran\"\n" + (if (starts) "echo $commit > \"${said.path}/soma-home/update/started\"\n" else "") + "exit 0\n")
            setExecutable(true)
        }
        sh("codesign", "--force", "-s", "-", File(at, "Soma.app").path)
    }

    @Test fun walk() {
        val out = out ?: return
        out.deleteRecursively(); out.mkdirs()
        val work = Files.createTempDirectory("soma-walk").toFile()
        val log = StringBuilder()
        fun note(line: String) { log.appendLine(line); println("WALK $line") }

        // 1. A scratch checkout with this branch's runner, a stand-in app zipped as the build job zips Soma.app, published.
        val repo = File(work, "repo").apply { mkdirs() }
        File(repo, "tools/mac-runner").mkdirs()
        for (name in listOf("oma-runner", "soma_services.py")) File(repoRoot, "tools/mac-runner/$name").copyTo(File(repo, "tools/mac-runner/$name"))
        sh("git", "init", "-q", dir = repo)
        sh("git", "add", ".", dir = repo)
        sh("git", "-c", "user.name=walk", "-c", "user.email=walk@walk", "commit", "-qm", "Enter sends on the Mac too", dir = repo)
        val head = sh("git", "rev-parse", "HEAD", dir = repo).trim()
        val commit = head.take(8)
        val built = File(work, "built").apply { mkdirs() }
        fakeApp(built, commit, starts = true, said = work)
        sh("ditto", "-c", "-k", "--keepParent", File(built, "Soma.app").path, File(work, "Soma.zip").path)
        File(work, "receipt.json").writeText(JSONObject().put("commit", head).put("suites", listOf(mapOf("name" to "walk", "exit" to 0))).toString())
        val release = File(work, "release")
        note(sh("python3", File(repoRoot, "tools/soma-release/soma-release").path, "publish", "--repo", repo.path, "--receipt", File(work, "receipt.json").path,
            "--artifact", "macos-arm64=${File(work, "Soma.zip").path}", env = mapOf("OMA_RUNNER_RELEASE" to release.path)).trim())

        // 2. This branch's runner on a spare port, letting in one throwaway key, serving that release folder.
        val port = ServerSocket(0).use { it.localPort }
        val key = "walk-" + "k".repeat(40)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val state = File(work, "runner").apply { mkdirs() }
        File(state, "Projects").mkdirs()
        File(state, "keys.json").writeText("""{"devices": [{"name": "walk", "digest": "sha256:$digest", "added": 0}]}""")
        File(state, "whois.json").writeText("""{"127.0.0.1": "walk"}""")
        val r = "${repoRoot.path}/tools/mac-runner"
        val runner = ProcessBuilder("python3", "$r/oma-runner", "serve").apply {
            environment().putAll(mapOf("OMA_RUNNER_PORT" to "$port", "OMA_RUNNER_BIND" to "127.0.0.1", "OMA_RUNNER_STATE" to "${state.path}/state", "OMA_RUNNER_LOGS" to "${state.path}/logs",
                "OMA_RUNNER_ROOTS" to "${state.path}/Projects", "OMA_RUNNER_KEYS" to "${state.path}/keys.json", "OMA_RUNNER_TOKEN" to "${state.path}/token",
                "OMA_RUNNER_WHOIS_FILE" to "${state.path}/whois.json", "OMA_RUNNER_PHONE" to "walk", "OMA_RUNNER_MAIL" to "${state.path}/mail",
                "OMA_RUNNER_CODEX_DISCOVER" to "0", "OMA_RUNNER_CLAUDE_DISCOVER" to "0", "OMA_RUNNER_AGENT" to "$r/fake-agent", "OMA_RUNNER_CODEX" to "$r/fake-codex",
                "OMA_RUNNER_CURSOR" to "$r/fake-cursor", "OMA_RUNNER_GROK" to "${state.path}/no-grok", "OMA_RUNNER_BOTS" to "${state.path}/bots",
                "OMA_RUNNER_ARTIFACTS" to "${state.path}/artifacts", "OMA_RUNNER_PROJECTS" to "${state.path}/projects.json", "OMA_RUNNER_RELEASE" to release.path, "HOME" to state.path))
        }.redirectErrorStream(true).start()
        try {
            runner.inputStream.bufferedReader().readLine()
            val self = Mesh.selfName() ?: "walk-mac"
            val machines = listOf(MacMachine(self, "this Mac (walk runner)", MacRunnerConfig("http://127.0.0.1:$port", key)))
            val old = File(work, "Applications").apply { mkdirs() }
            fakeApp(old, "85a29c49", starts = true, said = work)
            val dir = File(work, "soma-home/update")
            val did = mutableListOf<String>()
            val real = DesktopUpdateHands({ machines }, dir, quitting = { did += "quit" }, app = File(old, "Soma.app"))
            val hands = object : UpdateHands by real {
                override fun swapRunner(tarball: File, release: Release): String? {
                    // What install would be given: the tarball unpacks, and its stamp is the build's commit.
                    val into = File(tarball.parentFile, "runner-check").apply { mkdirs() }
                    sh("tar", "-xzf", tarball.path, "-C", into.path)
                    did += "runner install (recorded): stamp ${File(into, "oma-runner.build").readText().trim()}"
                    return null
                }
                override fun startSwap(release: Release): String? { did += "swap helper (run below)"; return null }
            }
            val updater = Updater(hands, dir, SomaBuild("85a29c49", 1_000), "macos-arm64", "walk")

            // 3. The pill, his tap, what changed, his tap on update and restart; each card as it showed.
            runComposeUiTest {
                updater.check()
                setContent { Box(Modifier.size(560.dp, 440.dp).background(Pack.background).padding(12.dp)) { Column { Row { UpdatePill(updater) }; UpdateSheet(updater) } } }
                waitForIdle()
                fun shot(name: String) = javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
                shot("1-pill")
                onNodeWithText("update soma · $commit", ignoreCase = true).performClick()
                waitForIdle()
                shot("2-what-changed")
                note("offered: ${(updater.state as UpdateState.Offer).release.let { "${it.commit} from ${it.runner}, notes ${it.notes}" }}")
                onNodeWithText("[ update and restart ]").performClick()
                waitUntil(timeoutMillis = 30_000) { did.contains("quit") || updater.state is UpdateState.Failed }
                waitForIdle()
                shot("3-after-tap")
            }
            note("state: ${updater.state}")
            note("did: $did")
            assertEquals(UpdateState.Updating(updater.state.let { (it as? UpdateState.Updating)?.release ?: error("not updating: $it") }, 3), updater.state)
            val staged = File(dir, "$commit/app/Soma.app")
            assertTrue("staged", staged.isDirectory)
            note("fetched and sha-checked: ${File(dir, commit).list()?.sorted()}")

            // 4. The swap helper on the stand-in app, as Soma starts it after quitting (here its "Soma" has quit already).
            val gone = ProcessBuilder("true").start().apply { waitFor() }.pid().toString()
            fun helper(newApp: File): JSONObject {
                File(dir, "result.json").delete()
                val p = ProcessBuilder("/bin/sh", File(dir, "swap.sh").apply { writeText(SWAP_SCRIPT) }.path, "mac", gone, File(old, "Soma.app").path, newApp.path, commit, "85a29c49", dir.path)
                    .apply { environment()["SOMA_SWAP_WAIT"] = "8"; environment()["HOME"] = work.path }.redirectErrorStream(true).start()
                note("helper: " + p.inputStream.bufferedReader().readText().trim().replace("\n", " | "))
                p.waitFor(60, TimeUnit.SECONDS)
                return JSONObject(File(dir, "result.json").readText())
            }
            val good = helper(staged)
            note("helper result (starts): $good")
            assertTrue(good.getBoolean("ok"))
            assertTrue(File(old, "Soma.app.prev").isDirectory)
            assertEquals(commit, File(work, "ran").readLines().last())

            // And a new app that doesn't start: the old one is put back and started, and the result says why.
            val broken = File(work, "broken").apply { mkdirs() }
            fakeApp(broken, "deadbeef", starts = false, said = work)
            val bad = helper(File(broken, "Soma.app"))
            note("helper result (doesn't start): $bad")
            assertTrue(!bad.getBoolean("ok") && bad.getString("step") == "start")
            Thread.sleep(3_000)  // open returns before the app runs
            note("ran, in order: ${File(work, "ran").readLines()}")
            assertEquals(listOf(commit, "deadbeef", commit), File(work, "ran").readLines())

            // 5. The old Soma, started again, reads the failure: shown to him, and the runner put back (recorded here).
            val back = mutableListOf<String>()
            val after = Updater(object : UpdateHands by hands {
                override fun working(): List<String>? = emptyList()
                override fun restoreRunner(): String? { back += "runner put back (recorded)"; return null }
            }, dir, SomaBuild("85a29c49", 1_000), "macos-arm64", "walk")
            after.reportAtStart()
            note("old Soma at start: ${after.state.let { (it as? UpdateState.Failed)?.reason ?: it.toString() }}; $back")
            runComposeUiTest {
                after.open = true
                setContent { Box(Modifier.size(560.dp, 300.dp).background(Pack.background).padding(12.dp)) { Column { Row { UpdatePill(after) }; UpdateSheet(after) } } }
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "4-didnt-update.png"))
            }
        } finally {
            runner.destroy()
            File(out, "walk.txt").writeText(log.toString())
        }
    }
}
